package app.lawnchair.backup

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import app.lawnchair.LawnchairProto.BackupInfo
import app.lawnchair.util.hasFlag
import app.lawnchair.util.scaleDownTo
import app.lawnchair.util.scaleDownToDisplaySize
import app.lawnchair.wallpaper.WallpaperColorsCompat
import app.lawnchair.wallpaper.WallpaperManagerCompat
import com.android.launcher3.BuildConfig
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherFiles
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
import com.android.launcher3.model.DeviceGridState
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.provider.RestoreDbTask
import com.google.protobuf.Timestamp
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LawnchairBackup(
    private val context: Context,
    private val uri: Uri,
) {
    lateinit var info: BackupInfo
    var screenshot: Bitmap? = null
    var wallpaper: Bitmap? = null
    private var archiveSnapshot: File? = null

    /** Releases preview bitmaps and the private archive snapshot when the backup is no longer used. */
    fun close() {
        screenshot?.recycle()
        screenshot = null
        wallpaper?.recycle()
        wallpaper = null
        archiveSnapshot?.delete()
        archiveSnapshot = null
    }

    suspend fun readInfoAndPreview() {
        createArchiveSnapshot()
        var tmpScreenshot: Bitmap? = null
        var tmpWallpaper: Bitmap? = null
        try {
            scanArchive(setOf(INFO_FILE_NAME))
            readZip(
                mapOf(
                    INFO_FILE_NAME to { info = BackupInfo.newBuilder().mergeFrom(it).build() },
                    SCREENSHOT_FILE_NAME to { tmpScreenshot = decodeBoundedBitmap(it) },
                    WALLPAPER_FILE_NAME to { tmpWallpaper = decodeBoundedBitmap(it) },
                ),
            )
            require(::info.isInitialized) { "Missing backup metadata" }
            validateInfo(info)
            val size = max(info.previewWidth, info.previewHeight).coerceAtMost(MAX_PREVIEW_SIZE)
            screenshot?.recycle()
            wallpaper?.recycle()
            val previewScreenshot = tmpScreenshot?.scaleDownTo(size)
            val previewWallpaper = tmpWallpaper?.scaleDownToDisplaySize(context)
            if (previewScreenshot !== tmpScreenshot) tmpScreenshot?.recycle()
            if (previewWallpaper !== tmpWallpaper) tmpWallpaper?.recycle()
            screenshot = previewScreenshot
            wallpaper = previewWallpaper
        } catch (throwable: Throwable) {
            archiveSnapshot?.delete()
            archiveSnapshot = null
            tmpScreenshot?.recycle()
            tmpWallpaper?.recycle()
            throw throwable
        }
    }

    /**
     * Restores only the selected sections. In particular, a wallpaper-only restore must never
     * touch launcher databases, preferences, or grid state.
     */
    suspend fun restore(selectedContents: Int) {
        require(::info.isInitialized) { "Backup metadata has not been read" }
        validateInfo(info)

        val contents = selectedContents and info.contents
        if (contents == 0) {
            close()
            return
        }

        val restoreLayout = contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)
        val restoreWallpaper = contents.hasFlag(INCLUDE_WALLPAPER)
        val requiredEntries = buildSet {
            add(INFO_FILE_NAME)
            if (restoreLayout) add(LAUNCHER_DB_FILE_NAME)
            if (restoreWallpaper) add(WALLPAPER_FILE_NAME)
        }
        requireNotNull(archiveSnapshot?.takeIf(File::isFile)) {
            "Backup archive snapshot is unavailable; read backup metadata again"
        }

        val stagingDir = File(context.cacheDir, "lawnchair_restore_${System.nanoTime()}")
        val stagedFiles = mutableMapOf<File, File>()
        val rollbackFiles = mutableMapOf<File, File?>()
        val modifiedTargets = mutableSetOf<File>()
        var databaseRollbackSnapshotsReady = false
        val sqliteRollbackDatabases =
            if (restoreLayout) {
                (
                    LauncherFiles.GRID_DB_FILES.map(context::getDatabasePath) +
                        context.getDatabasePath(PREFS_DB_FILE_NAME)
                    ).distinct()
            } else {
                emptyList()
            }
        val databaseRollbackTargets = sqliteRollbackDatabases.flatMap { database ->
            listOf(
                database,
                File(database.path + "-wal"),
                File(database.path + "-journal"),
                File(database.path + "-shm"),
            )
        }.distinct()
        var stagedWallpaper: Bitmap? = null

        try {
            // Keep validation inside the cleanup scope: a malformed archive must not leave its
            // private snapshot or preview bitmaps behind when validation fails.
            scanArchive(requiredEntries)
            require(stagingDir.mkdirs()) { "Unable to create restore staging directory" }

            if (restoreLayout) {
                val restoreTargets = getFiles(context, forRestore = true)
                val handlers: Map<String, suspend (InputStream) -> Unit> =
                    restoreTargets.mapValues { (entryName, target) ->
                        { input: InputStream ->
                            val staged = File(stagingDir, "files/${entryName.replace('/', '_')}")
                            staged.parentFile?.mkdirs()
                            staged.outputStream().use { output -> input.copyTo(output) }
                            stagedFiles[target] = staged
                        }
                    }
                readZip(handlers)
                val stagedDatabase = stagedFiles.entries
                    .firstOrNull { it.key.name == RESTORED_DB_FILE_NAME }
                    ?.value ?: throw IllegalArgumentException("Backup does not contain a launcher database")
                validateLauncherDatabase(stagedDatabase)
            }

            if (restoreWallpaper) {
                readZip(
                    mapOf(
                        WALLPAPER_FILE_NAME to { input ->
                            stagedWallpaper = decodeBoundedBitmap(input)
                                ?: throw IllegalArgumentException("Invalid wallpaper image")
                        },
                    ),
                )
                require(stagedWallpaper != null) { "Backup does not contain a valid wallpaper" }
            }

            // Checkpoint live grid databases before taking rollback snapshots so committed WAL
            // pages are represented by the main database file.
            withContext(Dispatchers.IO) {
                if (restoreLayout) checkpointLauncherDatabases(sqliteRollbackDatabases)
                val rollbackTargets = (stagedFiles.keys + databaseRollbackTargets).distinct()
                rollbackTargets.forEachIndexed { index, target ->
                    if (target.exists() && !target.name.endsWith("-shm")) {
                        val rollback = File(stagingDir, "rollback/$index")
                        rollback.parentFile?.mkdirs()
                        target.copyTo(rollback, overwrite = true)
                        rollbackFiles[target] = rollback
                    } else {
                        // A null snapshot records that the file did not exist before restore.
                        // SQLite shared-memory files are transient and are always regenerated.
                        rollbackFiles[target] = null
                    }
                }
                databaseRollbackSnapshotsReady = true

                if (restoreLayout) {
                    stagedFiles.forEach { (target, staged) ->
                        target.parentFile?.mkdirs()
                        if (target.name == PREFS_DB_FILE_NAME) {
                            // The preferences SQLite file is replaced as part of a layout restore;
                            // stale sidecars must not be replayed against the restored database.
                            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                                val sidecar = File(target.path + suffix)
                                modifiedTargets.add(sidecar)
                                sidecar.delete()
                            }
                        }
                        modifiedTargets.add(target)
                        staged.copyTo(target, overwrite = true)
                    }
                }
            }

            if (restoreLayout) {
                // Preferences are restored from the archive, so the grid state must be applied
                // afterwards or the restored preferences could overwrite it.
                DeviceGridState(info.gridState).writeToPrefs(context, true)
                val restoreController = ModelDbController(context, true)
                val restoreSucceeded = try {
                    RestoreDbTask.performRestore(context, restoreController)
                } finally {
                    restoreController.close()
                }
                check(restoreSucceeded) { "Launcher database restore failed" }
                // The explicit restore above also handles widget-ID remapping while the pending
                // flag is present. Clear it only after the database restore has succeeded.
                LauncherPrefs.get(context).removeSync(LauncherPrefs.RESTORE_DEVICE)
            }

            if (restoreWallpaper) {
                WallpaperManager.getInstance(context).setBitmap(requireNotNull(stagedWallpaper))
            }
        } catch (throwable: Throwable) {
            // Restore launcher databases before their WAL/journal sidecars. SHM is transient and
            // must be regenerated by SQLite rather than copied from an earlier connection.
            if (databaseRollbackSnapshotsReady) {
                modifiedTargets.addAll(databaseRollbackTargets)
            }
            val rollbackFailures = withContext(Dispatchers.IO) {
                buildList {
                    modifiedTargets.sortedBy {
                        if (it.name.endsWith("-wal") || it.name.endsWith("-journal")) 1 else 0
                    }.forEach { target ->
                        runCatching {
                            val rollback = rollbackFiles[target]
                            if (rollback != null && rollback.exists()) {
                                target.parentFile?.mkdirs()
                                rollback.copyTo(target, overwrite = true)
                            } else {
                                check(!target.exists() || target.delete()) {
                                    "Unable to remove partially restored file: ${target.name}"
                                }
                            }
                        }.exceptionOrNull()?.let(::add)
                    }
                }
            }
            rollbackFailures.forEach(throwable::addSuppressed)
            throw throwable
        } finally {
            stagedWallpaper?.recycle()
            stagingDir.deleteRecursively()
            close()
        }
    }

    private suspend fun createArchiveSnapshot() = withContext(Dispatchers.IO) {
        archiveSnapshot?.delete()
        archiveSnapshot = null
        val snapshot = File.createTempFile("lawnchair_backup_", ".zip", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Unable to open backup file")
            input.use { source ->
                FileOutputStream(snapshot).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalBytes = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        require(totalBytes <= MAX_ARCHIVE_BYTES) {
                            "Backup archive exceeds the supported compressed size"
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            archiveSnapshot = snapshot
        } catch (throwable: Throwable) {
            snapshot.delete()
            throw throwable
        }
    }

    private suspend fun readZip(handlers: Map<String, suspend (InputStream) -> Unit>) {
        withContext(Dispatchers.IO) {
            val snapshot = requireNotNull(archiveSnapshot?.takeIf(File::isFile)) {
                "Backup archive snapshot is unavailable"
            }
            FileInputStream(snapshot).use { input ->
                ZipInputStream(input).use { zip ->
                    val seen = mutableSetOf<String>()
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(!entry.isDirectory && isAllowedEntry(entry.name)) {
                            "Unsupported backup entry: ${entry.name}"
                        }
                        require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                        handlers[entry.name]?.invoke(zip)
                        zip.closeEntry()
                        require(seen.size <= MAX_ENTRY_COUNT) { "Too many backup entries" }
                    }
                }
            }
        }
    }

    /**
     * Performs a bounded, complete pass before any restore target is modified. Reading every
     * entry also forces ZIP CRC validation and detects truncated/corrupt archives.
     */
    private suspend fun scanArchive(requiredEntries: Set<String>) = withContext(Dispatchers.IO) {
        val snapshot = requireNotNull(archiveSnapshot?.takeIf(File::isFile)) {
            "Backup archive snapshot is unavailable"
        }
        validateCentralDirectory(snapshot)
        val seen = mutableSetOf<String>()
        var totalBytes = 0L
        ZipFile(snapshot).use { zip ->
            val entries = zip.entries()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(!entry.isDirectory && isAllowedEntry(entry.name)) {
                    "Unsupported backup entry: ${entry.name}"
                }
                require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                val declaredSize = entry.size
                val compressedSize = entry.compressedSize
                require(declaredSize >= 0L && compressedSize >= 0L) {
                    "Backup entry has unknown ZIP size metadata: ${entry.name}"
                }
                require(declaredSize <= MAX_ENTRY_BYTES) {
                    "Backup entry exceeds the supported size: ${entry.name}"
                }
                require(
                    entry.method == ZipEntry.STORED || entry.method == ZipEntry.DEFLATED,
                ) { "Unsupported ZIP compression method: ${entry.method}" }
                require(
                    declaredSize == 0L && compressedSize == 0L ||
                        compressedSize > 0L &&
                        declaredSize <= compressedSize * MAX_COMPRESSION_RATIO,
                ) { "Suspicious ZIP compression ratio: ${entry.name}" }

                var entryBytes = 0L
                zip.getInputStream(entry).use { stream ->
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        entryBytes += count
                        totalBytes += count
                        require(
                            entryBytes <= MAX_ENTRY_BYTES &&
                                totalBytes <= MAX_ARCHIVE_BYTES &&
                                entryBytes <= declaredSize,
                        ) { "Backup archive exceeds its declared or supported size" }
                    }
                }
                require(entryBytes == declaredSize) {
                    "ZIP entry size does not match its central-directory metadata: ${entry.name}"
                }
                require(seen.size <= MAX_ENTRY_COUNT) { "Too many backup entries" }
            }
        }
        require(seen.containsAll(requiredEntries)) {
            "Backup is missing required entries: ${requiredEntries - seen}"
        }
    }

    /**
     * Bound central-directory parsing before ZipFile allocates entry metadata. ZIP permits a
     * 64 KiB comment, so the complete EOCD record is always inside this bounded tail window.
     */
    private fun validateCentralDirectory(snapshot: File) {
        RandomAccessFile(snapshot, "r").use { archive ->
            val fileLength = archive.length()
            require(fileLength >= ZIP_EOCD_MIN_SIZE) { "Backup is not a valid ZIP archive" }
            val tailLength =
                minOf(fileLength, (ZIP_EOCD_MIN_SIZE + MAX_ZIP_COMMENT_BYTES).toLong()).toInt()
            val tail = ByteArray(tailLength)
            archive.seek(fileLength - tailLength)
            archive.readFully(tail)

            var eocdOffset = -1
            for (offset in tail.size - ZIP_EOCD_MIN_SIZE downTo 0) {
                if (readUInt32LittleEndian(tail, offset) != ZIP_EOCD_SIGNATURE) continue
                val commentLength = readUInt16LittleEndian(tail, offset + 20)
                if (offset + ZIP_EOCD_MIN_SIZE + commentLength == tail.size) {
                    eocdOffset = offset
                    break
                }
            }
            require(eocdOffset >= 0) { "ZIP end-of-central-directory record is missing" }

            val diskNumber = readUInt16LittleEndian(tail, eocdOffset + 4)
            val centralDirectoryDisk = readUInt16LittleEndian(tail, eocdOffset + 6)
            val entriesOnDisk = readUInt16LittleEndian(tail, eocdOffset + 8)
            val totalEntries = readUInt16LittleEndian(tail, eocdOffset + 10)
            val centralDirectorySize = readUInt32LittleEndian(tail, eocdOffset + 12)
            val centralDirectoryOffset = readUInt32LittleEndian(tail, eocdOffset + 16)
            require(diskNumber == 0 && centralDirectoryDisk == 0 && entriesOnDisk == totalEntries) {
                "Multi-disk ZIP archives are not supported"
            }
            require(
                totalEntries != ZIP16_SENTINEL &&
                    centralDirectorySize != ZIP32_SENTINEL &&
                    centralDirectoryOffset != ZIP32_SENTINEL,
            ) { "ZIP64 archives are not supported" }
            require(totalEntries in 1..MAX_ENTRY_COUNT) {
                "Backup archive contains an unsupported number of entries"
            }
            require(
                centralDirectorySize <= MAX_CENTRAL_DIRECTORY_BYTES &&
                    centralDirectorySize >= totalEntries.toLong() * ZIP_CENTRAL_DIRECTORY_HEADER_SIZE,
            ) { "ZIP central-directory metadata exceeds the supported limit" }

            val absoluteEocdOffset = fileLength - tailLength + eocdOffset
            require(
                centralDirectoryOffset + centralDirectorySize <= absoluteEocdOffset,
            ) { "ZIP central-directory metadata is out of bounds" }
        }
    }

    private fun readUInt16LittleEndian(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readUInt32LittleEndian(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    /**
     * Decode untrusted archive images only after bounding their encoded size and decoded pixel
     * count. The bounds pass uses an in-memory snapshot so it does not consume the ZIP stream.
     */
    private fun decodeBoundedBitmap(input: InputStream): Bitmap? {
        val encoded = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytes = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            totalBytes += count
            require(totalBytes <= MAX_IMAGE_BYTES) {
                "Backup image exceeds the supported size"
            }
            encoded.write(buffer, 0, count)
        }

        val bytes = encoded.toByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Invalid backup image"
        }
        require(bounds.outWidth <= MAX_IMAGE_DIMENSION && bounds.outHeight <= MAX_IMAGE_DIMENSION) {
            "Backup image dimensions exceed the supported limit"
        }

        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize.toLong() * (bounds.outHeight / sampleSize.toLong()) >
            MAX_IMAGE_PIXELS
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }

    private fun checkpointLauncherDatabases(databaseFiles: List<File>) {
        databaseFiles.forEach { databaseFile ->
            if (!databaseFile.isFile || databaseFile.length() == 0L) return@forEach
            val database = SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            database.use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.columnCount >= 3) {
                        "Unable to verify checkpoint for database ${databaseFile.name}"
                    }
                    val busy = cursor.getInt(0)
                    val walFrames = cursor.getInt(1)
                    val checkpointedFrames = cursor.getInt(2)
                    check(busy == 0 && (walFrames < 0 || checkpointedFrames >= walFrames)) {
                        "Incomplete WAL checkpoint for database ${databaseFile.name} " +
                            "(busy=$busy, walFrames=$walFrames, checkpointed=$checkpointedFrames)"
                    }
                }
            }
        }
    }

    private fun validateLauncherDatabase(file: File) {
        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        database.use { db ->
            db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "Launcher database failed integrity validation"
                }
            }
            db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='favorites'", null)
                .use { cursor ->
                    require(cursor.moveToFirst()) { "Launcher database has no favorites table" }
                }
        }
    }

    private fun validateInfo(backupInfo: BackupInfo) {
        require(backupInfo.backupVersion == BACKUP_VERSION) {
            "Unsupported backup version: ${backupInfo.backupVersion}"
        }
        require(backupInfo.contents != 0 && (backupInfo.contents and KNOWN_CONTENTS.inv()) == 0) {
            "Invalid backup contents"
        }
        require(backupInfo.previewWidth >= 0 && backupInfo.previewHeight >= 0) {
            "Invalid preview dimensions"
        }
    }

    private fun isAllowedEntry(name: String): Boolean = name in setOf(
        INFO_FILE_NAME,
        SCREENSHOT_FILE_NAME,
        WALLPAPER_FILE_NAME,
        LAUNCHER_DB_FILE_NAME,
        PREFS_FILE_NAME,
        PREFS_DB_FILE_NAME,
        PREFS_DATASTORE_FILE_NAME,
    ) && !name.contains("..") && !name.startsWith("/")

    companion object {
        private const val BACKUP_VERSION = 1
        private const val MAX_PREVIEW_SIZE = 4000
        private const val MAX_IMAGE_DIMENSION = 32768
        private const val MAX_IMAGE_PIXELS = 16L * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 64 * 1024 * 1024
        private const val MAX_ENTRY_COUNT = 16
        private const val MAX_COMPRESSION_RATIO = 1000L
        private const val MAX_CENTRAL_DIRECTORY_BYTES = 64L * 1024
        private const val MAX_ZIP_COMMENT_BYTES = 65535
        private const val ZIP_EOCD_MIN_SIZE = 22
        private const val ZIP_CENTRAL_DIRECTORY_HEADER_SIZE = 46L
        private const val ZIP_EOCD_SIGNATURE = 0x06054b50L
        private const val ZIP16_SENTINEL = 0xffff
        private const val ZIP32_SENTINEL = 0xffffffffL
        private const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
        private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
        private const val KNOWN_CONTENTS = (1 shl 0) or (1 shl 1)
        private const val PREFS_FILE_NAME = "${LauncherFiles.SHARED_PREFERENCES_KEY}.xml"
        private const val PREFS_DB_FILE_NAME = "preferences"
        private const val PREFS_DATASTORE_FILE_NAME = "preferences.preferences_pb"

        const val INFO_FILE_NAME = "info.pb"
        const val WALLPAPER_FILE_NAME = "wallpaper.png"
        const val SCREENSHOT_FILE_NAME = "screenshot.png"
        const val LAUNCHER_DB_FILE_NAME = "launcher.db"
        const val RESTORED_DB_FILE_NAME = "restored.db"

        const val INCLUDE_LAYOUT_AND_SETTINGS = 1 shl 0
        const val INCLUDE_WALLPAPER = 1 shl 1

        const val MIME_TYPE = "application/zip"
        val EXTRA_MIME_TYPES = arrayOf(MIME_TYPE, "application/x-zip", "application/octet-stream")

        val contentOptions = listOf(
            INCLUDE_LAYOUT_AND_SETTINGS to R.string.backup_content_layout_and_settings,
            INCLUDE_WALLPAPER to R.string.backup_content_wallpaper,
        )

        fun generateBackupFileName(): String {
            val fileName = "Lawnchair_Backup ${SimpleDateFormat.getDateTimeInstance().format(Date())}"
            return "$fileName.lawnchairbackup"
        }

        fun getFiles(context: Context, forRestore: Boolean): Map<String, File> {
            return mapOf(
                LAUNCHER_DB_FILE_NAME to launcherDbFile(context, forRestore),
                PREFS_FILE_NAME to prefsFile(context),
                PREFS_DB_FILE_NAME to prefsDbFile(context),
                PREFS_DATASTORE_FILE_NAME to prefsDataStoreFile(context),
            )
        }

        @SuppressLint("MissingPermission")
        suspend fun create(context: Context, contents: Int, screenshotBitmap: Bitmap, fileUri: Uri) {
            require(contents != 0 && (contents and KNOWN_CONTENTS.inv()) == 0) {
                "Invalid backup contents"
            }
            val idp = LauncherAppState.getIDP(context)
            val createdAt = Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000)
            val colorHints = WallpaperManagerCompat.INSTANCE.get(context).wallpaperColors?.colorHints ?: 0
            val wallpaperSupportsDarkText =
                (colorHints and WallpaperColorsCompat.HINT_SUPPORTS_DARK_TEXT) != 0
            val info = BackupInfo.newBuilder()
                .setLawnchairVersion(BuildConfig.VERSION_CODE)
                .setBackupVersion(BACKUP_VERSION)
                .setCreatedAt(createdAt)
                .setContents(contents)
                .setGridState(DeviceGridState(idp).toProtoMessage())
                .setPreviewWidth(screenshotBitmap.width)
                .setPreviewHeight(screenshotBitmap.height)
                .setPreviewDarkText(wallpaperSupportsDarkText)
                .build()

            val sourceFiles = if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
                getFiles(context, forRestore = false).also { files ->
                    val launcherDatabase = files[LAUNCHER_DB_FILE_NAME]
                    require(launcherDatabase?.isFile == true) {
                        "Launcher database is not available for backup"
                    }
                    val existingFiles = files.values.filter { it.isFile }
                    require(existingFiles.all { it.length() <= MAX_ENTRY_BYTES }) {
                        "A backup source file exceeds the supported size"
                    }
                    require(existingFiles.sumOf { it.length() } <= MAX_ARCHIVE_BYTES) {
                        "Backup sources exceed the supported size"
                    }
                }
            } else {
                emptyMap()
            }
            val wallpaperBitmap = if (contents.hasFlag(INCLUDE_WALLPAPER)) {
                WallpaperManager.getInstance(context).drawable?.toBitmap()
                    ?: throw IllegalStateException("Wallpaper could not be read")
            } else {
                null
            }
            try {
                val pfd = context.contentResolver.openFileDescriptor(fileUri, "w")
                    ?: throw IllegalArgumentException("Unable to create backup file")
                withContext(Dispatchers.IO) {
                    pfd.use {
                        ZipOutputStream(FileOutputStream(pfd.fileDescriptor).buffered()).use { out ->
                            out.putNextEntry(ZipEntry(INFO_FILE_NAME))
                            info.writeTo(out)
                            out.closeEntry()

                            if (contents.hasFlag(INCLUDE_WALLPAPER)) {
                                out.putNextEntry(ZipEntry(WALLPAPER_FILE_NAME))
                                require(requireNotNull(wallpaperBitmap).compress(Bitmap.CompressFormat.PNG, 100, out)) {
                                    "Unable to encode wallpaper"
                                }
                                out.closeEntry()
                            }
                            if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
                                out.putNextEntry(ZipEntry(SCREENSHOT_FILE_NAME))
                                require(screenshotBitmap.compress(Bitmap.CompressFormat.PNG, 85, out)) {
                                    "Unable to encode backup preview"
                                }
                                out.closeEntry()

                                sourceFiles.forEach { (entryName, file) ->
                                    if (!file.isFile) return@forEach
                                    out.putNextEntry(ZipEntry(entryName))
                                    file.inputStream().use { input -> input.copyTo(out) }
                                    out.closeEntry()
                                }
                            }
                        }
                    }
                }
            } finally {
                // This bitmap is created here; the screenshot bitmap remains caller-owned.
                wallpaperBitmap?.recycle()
            }
        }

        private fun launcherDbFile(context: Context, forRestore: Boolean): File {
            val dbName = if (forRestore) RESTORED_DB_FILE_NAME else LauncherAppState.getIDP(context).dbFile
            return context.getDatabasePath(dbName)
        }

        private fun prefsFile(context: Context): File {
            val dir = context.cacheDir.parent
            return File(dir, "shared_prefs/$PREFS_FILE_NAME")
        }

        private fun prefsDbFile(context: Context): File = context.getDatabasePath(PREFS_DB_FILE_NAME)

        private fun prefsDataStoreFile(context: Context): File = File(context.filesDir, "datastore/$PREFS_DATASTORE_FILE_NAME")
    }
}
