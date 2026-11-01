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
import com.android.launcher3.R
import com.android.launcher3.model.DeviceGridState
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.provider.RestoreDbTask
import com.google.protobuf.Timestamp
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
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

    suspend fun readInfoAndPreview() {
        scanArchive(setOf(INFO_FILE_NAME))
        var tmpScreenshot: Bitmap? = null
        var tmpWallpaper: Bitmap? = null
        readZip(
            mapOf(
                INFO_FILE_NAME to { info = BackupInfo.newBuilder().mergeFrom(it).build() },
                SCREENSHOT_FILE_NAME to { tmpScreenshot = BitmapFactory.decodeStream(it) },
                WALLPAPER_FILE_NAME to { tmpWallpaper = BitmapFactory.decodeStream(it) },
            ),
        )
        require(::info.isInitialized) { "Missing backup metadata" }
        validateInfo(info)
        val size = max(info.previewWidth, info.previewHeight).coerceAtMost(MAX_PREVIEW_SIZE)
        screenshot = tmpScreenshot?.scaleDownTo(size)
        wallpaper = tmpWallpaper?.scaleDownToDisplaySize(context)
    }

    /**
     * Restores only the selected sections. In particular, a wallpaper-only restore must never
     * touch launcher databases, preferences, or grid state.
     */
    suspend fun restore(selectedContents: Int) {
        require(::info.isInitialized) { "Backup metadata has not been read" }
        validateInfo(info)

        val contents = selectedContents and info.contents
        if (contents == 0) return

        val restoreLayout = contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)
        val restoreWallpaper = contents.hasFlag(INCLUDE_WALLPAPER)
        val requiredEntries = buildSet {
            add(INFO_FILE_NAME)
            if (restoreLayout) add(LAUNCHER_DB_FILE_NAME)
            if (restoreWallpaper) add(WALLPAPER_FILE_NAME)
        }
        scanArchive(requiredEntries)

        val stagingDir = File(context.cacheDir, "lawnchair_restore_${System.nanoTime()}")
        require(stagingDir.mkdirs()) { "Unable to create restore staging directory" }
        val stagedFiles = mutableMapOf<File, File>()
        val rollbackFiles = mutableMapOf<File, File>()
        val modifiedTargets = mutableSetOf<File>()
        var stagedWallpaper: Bitmap? = null

        try {
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
                            stagedWallpaper = BitmapFactory.decodeStream(input)
                                ?: throw IllegalArgumentException("Invalid wallpaper image")
                        },
                    ),
                )
                require(stagedWallpaper != null) { "Backup does not contain a valid wallpaper" }
            }

            // Keep exact-file rollback copies. Never recursively delete the databases directory:
            // it may contain unrelated databases and DataStore files.
            withContext(Dispatchers.IO) {
                stagedFiles.keys.forEachIndexed { index, target ->
                    if (target.exists()) {
                        val rollback = File(stagingDir, "rollback/$index")
                        rollback.parentFile?.mkdirs()
                        target.copyTo(rollback, overwrite = true)
                        rollbackFiles[target] = rollback
                    }
                }

                if (restoreLayout) {
                    stagedFiles.forEach { (target, staged) ->
                        target.parentFile?.mkdirs()
                        modifiedTargets.add(target)
                        staged.copyTo(target, overwrite = true)
                    }
                }
            }

            if (restoreLayout) {
                // Preferences are restored from the archive, so the grid state must be applied
                // afterwards or the restored preferences could overwrite it.
                DeviceGridState(info.gridState).writeToPrefs(context, true)
                RestoreDbTask.performRestore(context, ModelDbController(context))
            }

            if (restoreWallpaper) {
                WallpaperManager.getInstance(context).setBitmap(requireNotNull(stagedWallpaper))
            }
        } catch (throwable: Throwable) {
            // Restore the exact files modified by this operation if a later file operation fails.
            withContext(Dispatchers.IO) {
                modifiedTargets.forEach { target ->
                    runCatching {
                        val rollback = rollbackFiles[target]
                        if (rollback != null && rollback.exists()) {
                            rollback.copyTo(target, overwrite = true)
                        } else {
                            target.delete()
                        }
                    }
                }
            }
            throw throwable
        } finally {
            stagedWallpaper?.recycle()
            stagingDir.deleteRecursively()
        }
    }

    private suspend fun readZip(handlers: Map<String, suspend (InputStream) -> Unit>) {
        withContext(Dispatchers.IO) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalArgumentException("Unable to open backup file")
            pfd.use {
                FileInputStream(it.fileDescriptor).use { inStream ->
                    ZipInputStream(inStream).use { zipIs ->
                        val seen = mutableSetOf<String>()
                        while (true) {
                            val entry = zipIs.nextEntry ?: break
                            require(isAllowedEntry(entry.name)) { "Unexpected backup entry" }
                            require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                            handlers[entry.name]?.invoke(zipIs)
                            zipIs.closeEntry()
                        }
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
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Unable to open backup file")
        val seen = mutableSetOf<String>()
        var totalBytes = 0L
        pfd.use {
            FileInputStream(it.fileDescriptor).use { input ->
                ZipInputStream(input).use { zip ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(!entry.isDirectory && isAllowedEntry(entry.name)) {
                            "Unsupported backup entry: ${entry.name}"
                        }
                        require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                        var entryBytes = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            entryBytes += count
                            totalBytes += count
                            require(entryBytes <= MAX_ENTRY_BYTES && totalBytes <= MAX_ARCHIVE_BYTES) {
                                "Backup archive exceeds the supported size"
                            }
                        }
                        zip.closeEntry()
                        require(seen.size <= MAX_ENTRY_COUNT) { "Too many backup entries" }
                    }
                }
            }
        }
        require(seen.containsAll(requiredEntries)) {
            "Backup is missing required entries: ${requiredEntries - seen}"
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
        private const val MAX_ENTRY_COUNT = 16
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
