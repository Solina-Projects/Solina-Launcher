package app.lawnchair.backup

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.lawnchair.LawnchairProto.BackupInfo
import app.lawnchair.data.AppDatabase
import app.lawnchair.font.FontCache
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.util.hasFlag
import app.lawnchair.util.scaleDownTo
import app.lawnchair.util.scaleDownToDisplaySize
import app.lawnchair.wallpaper.WallpaperColorsCompat
import app.lawnchair.wallpaper.WallpaperManagerCompat
import com.android.launcher3.BuildConfig
import com.android.launcher3.InvariantDeviceProfile
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LawnchairBackup(
    private val context: Context,
    private val uri: Uri,
) {
    lateinit var info: BackupInfo
    var screenshot: Bitmap? = null
    var wallpaper: Bitmap? = null
    private var archiveSnapshot: File? = null
    private var archiveEntryNames: Set<String> = emptySet()

    /** Releases preview bitmaps and the private archive snapshot when the backup is no longer used. */
    fun close() {
        screenshot?.recycle()
        screenshot = null
        wallpaper?.recycle()
        wallpaper = null
        archiveSnapshot?.delete()
        archiveSnapshot = null
        archiveEntryNames = emptySet()
    }

    /**
     * Returns the bitmask of restorable sections available in this archive.
     * Legacy archives that stored all preferences under [INCLUDE_LAYOUT_AND_SETTINGS] expose the
     * granular section switches when the underlying preference files are present in the archive.
     */
    fun availableContents(): Int {
        if (!::info.isInitialized) return 0
        val raw = info.contents
        val hasGranularBits = (raw and GRANULAR_MODULE_FLAGS) != 0
        val hasLegacyPreferencePayload =
            MODULAR_SETTINGS_FILE_NAME in archiveEntryNames ||
                PREFS_FILE_NAME in archiveEntryNames ||
                PREFS_DATASTORE_FILE_NAME in archiveEntryNames
        return if (
            raw.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS) &&
            !hasGranularBits &&
            hasLegacyPreferencePayload
        ) {
            raw or GRANULAR_MODULE_FLAGS or INCLUDE_SUGGESTION_SETTINGS
        } else {
            raw
        }
    }

    suspend fun readInfoAndPreview() {
        createArchiveSnapshot()
        var tmpScreenshot: Bitmap? = null
        var tmpWallpaper: Bitmap? = null
        try {
            archiveEntryNames = scanArchive(setOf(INFO_FILE_NAME))
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
            archiveEntryNames = emptySet()
            tmpScreenshot?.recycle()
            tmpWallpaper?.recycle()
            throw throwable
        }
    }

    /**
     * Restores only the selected sections. Each launcher section can be restored independently or
     * together with any combination of other sections.
     */
    suspend fun restore(selectedContents: Int) {
        require(::info.isInitialized) { "Backup metadata has not been read" }
        validateInfo(info)
        val contents = selectedContents and availableContents()
        if (contents == 0) {
            close()
            return
        }

        val hasModularSettings = MODULAR_SETTINGS_FILE_NAME in archiveEntryNames
        val hasLegacyPrefsFiles =
            PREFS_FILE_NAME in archiveEntryNames ||
                PREFS_DATASTORE_FILE_NAME in archiveEntryNames ||
                PREFS_DB_FILE_NAME in archiveEntryNames

        val restoreHomeScreen = contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)
        val restoreDock = contents.hasFlag(INCLUDE_DOCK)
        val restoreLauncherDb =
            restoreHomeScreen || (restoreDock && LAUNCHER_DB_FILE_NAME in archiveEntryNames)
        val restoreWallpaper = contents.hasFlag(INCLUDE_WALLPAPER)
        val restoreSuggestions = contents.hasFlag(INCLUDE_SUGGESTION_SETTINGS)
        val restoreFonts = contents.hasFlag(INCLUDE_THEME_AND_FONTS)
        val restoreAnyModularSection = (contents and NON_WALLPAPER_CONTENTS) != 0

        val requiredEntries = buildSet {
            add(INFO_FILE_NAME)
            if (restoreHomeScreen || (restoreDock && !hasModularSettings)) {
                add(LAUNCHER_DB_FILE_NAME)
            }
            if (restoreWallpaper) add(WALLPAPER_FILE_NAME)
            if (restoreSuggestions && !hasModularSettings && !hasLegacyPrefsFiles) {
                add(SUGGESTION_SETTINGS_FILE_NAME)
            }
            if (hasModularSettings && restoreAnyModularSection) {
                add(MODULAR_SETTINGS_FILE_NAME)
            }
        }
        requireNotNull(archiveSnapshot?.takeIf(File::isFile)) {
            "Backup archive snapshot is unavailable; read backup metadata again"
        }
        val stagingDir = File(context.cacheDir, "lawnchair_restore_${System.nanoTime()}")
        val stagedFiles = mutableMapOf<File, File>()
        val stagedCustomFonts = mutableMapOf<File, File>()
        val stagedLegacyFiles = mutableMapOf<String, File>()
        val rollbackFiles = mutableMapOf<File, File?>()
        val modifiedTargets = mutableSetOf<File>()
        var databaseRollbackSnapshotsReady = false
        var preRestoreModularSnapshot: ByteArray? = null

        val activeLauncherDbBeforeRestore = launcherDbFile(context, forRestore = false)
        val sqliteRollbackDatabases =
            if (restoreLauncherDb || restoreAnyModularSection || restoreWallpaper) {
                (
                    LauncherFiles.GRID_DB_FILES.map(context::getDatabasePath) +
                        activeLauncherDbBeforeRestore +
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
        var stagedSuggestionSettings: ByteArray? = null
        var stagedModularSettings: ByteArray? = null

        try {
            archiveEntryNames = scanArchive(requiredEntries)
            require(stagingDir.mkdirs()) { "Unable to create restore staging directory" }

            val handlers = mutableMapOf<String, suspend (InputStream) -> Unit>()

            if (restoreLauncherDb) {
                val restoredDbTarget = launcherDbFile(context, forRestore = true)
                handlers[LAUNCHER_DB_FILE_NAME] = { input ->
                    val staged = File(stagingDir, "files/$LAUNCHER_DB_FILE_NAME")
                    staged.parentFile?.mkdirs()
                    staged.outputStream().use { output -> input.copyTo(output) }
                    stagedFiles[restoredDbTarget] = staged
                }
            }

            if (hasModularSettings && (restoreAnyModularSection || restoreWallpaper)) {
                handlers[MODULAR_SETTINGS_FILE_NAME] = { input ->
                    val payload = input.readBytes()
                    ModularBackupData.validateSnapshot(payload)
                    stagedModularSettings = payload
                }
            } else if (!hasModularSettings && hasLegacyPrefsFiles && restoreAnyModularSection) {
                listOf(PREFS_FILE_NAME, PREFS_DB_FILE_NAME, PREFS_DATASTORE_FILE_NAME).forEach { entryName ->
                    if (entryName in archiveEntryNames) {
                        handlers[entryName] = { input ->
                            val staged = File(stagingDir, "legacy/${entryName.replace('/', '_')}")
                            staged.parentFile?.mkdirs()
                            staged.outputStream().use { output -> input.copyTo(output) }
                            stagedLegacyFiles[entryName] = staged
                        }
                    }
                }
            }

            if (restoreFonts) {
                val fontsDir = FontCache.TTFFont.getFontsDir(context)
                archiveEntryNames.filter(::isCustomFontEntry).forEach { entryName ->
                    val fileName = entryName.removePrefix(CUSTOM_FONT_ENTRY_PREFIX)
                    val target = File(fontsDir, fileName)
                    handlers[entryName] = { input ->
                        val staged = File(stagingDir, "fonts/$fileName")
                        staged.parentFile?.mkdirs()
                        staged.outputStream().use { output -> input.copyTo(output) }
                        require(FontCache.TTFFont.createTypeface(staged) != null) {
                            "Invalid custom font file in backup: $fileName"
                        }
                        stagedCustomFonts[target] = staged
                    }
                }
            }

            if (restoreWallpaper || restoreHomeScreen) {
                val wallpaperDir = File(context.filesDir, "wallpapers")
                archiveEntryNames.filter(::isWallpaperLibraryEntry).forEach { entryName ->
                    val fileName = entryName.removePrefix(WALLPAPER_LIBRARY_ENTRY_PREFIX)
                    val target = File(wallpaperDir, fileName)
                    handlers[entryName] = { input ->
                        val staged = File(stagingDir, "files/wallpaper_$fileName")
                        staged.parentFile?.mkdirs()
                        staged.outputStream().use { output -> input.copyTo(output) }
                        stagedFiles[target] = staged
                    }
                }
            }

            if (restoreSuggestions && SUGGESTION_SETTINGS_FILE_NAME in archiveEntryNames) {
                handlers[SUGGESTION_SETTINGS_FILE_NAME] = { input ->
                    val payload = input.readBytes()
                    require(payload.size <= MAX_SUGGESTION_SETTINGS_BYTES) {
                        "Suggestion settings payload is too large"
                    }
                    validateSuggestionSettings(payload)
                    stagedSuggestionSettings = payload
                }
            }

            if (restoreWallpaper) {
                handlers[WALLPAPER_FILE_NAME] = { input ->
                    stagedWallpaper = decodeBoundedBitmap(input)
                        ?: throw IllegalArgumentException("Invalid wallpaper image")
                }
            }

            if (handlers.isNotEmpty()) {
                readZip(handlers)
            }

            if (restoreLauncherDb) {
                val stagedDatabase = stagedFiles.entries
                    .firstOrNull { it.key.name == RESTORED_DB_FILE_NAME }
                    ?.value ?: throw IllegalArgumentException("Backup does not contain a launcher database")
                validateLauncherDatabase(stagedDatabase)
            }

            if (restoreWallpaper) {
                require(stagedWallpaper != null) { "Backup does not contain a valid wallpaper" }
            }

            if (stagedModularSettings == null && stagedLegacyFiles.isNotEmpty()) {
                stagedModularSettings = ModularBackupData.createSnapshotFromLegacyFiles(
                    stagedSharedPrefsXml = stagedLegacyFiles[PREFS_FILE_NAME],
                    stagedDataStoreFile = stagedLegacyFiles[PREFS_DATASTORE_FILE_NAME],
                    stagedRoomDbFile = stagedLegacyFiles[PREFS_DB_FILE_NAME],
                    contents = contents,
                )
                ModularBackupData.validateSnapshot(requireNotNull(stagedModularSettings))
            }

            if (stagedModularSettings != null) {
                preRestoreModularSnapshot = ModularBackupData.exportSnapshot(context, ALL_CONTENTS_MASK)
            }

            var preRestoreActiveDbSnapshot: File? = null
            withContext(Dispatchers.IO) {
                if (sqliteRollbackDatabases.isNotEmpty()) {
                    checkpointLauncherDatabases(sqliteRollbackDatabases)
                }
                if (restoreLauncherDb &&
                    activeLauncherDbBeforeRestore.isFile &&
                    activeLauncherDbBeforeRestore.length() > 0L
                ) {
                    val snapshot = File(stagingDir, "pre_restore_active_launcher.db")
                    activeLauncherDbBeforeRestore.copyTo(snapshot, overwrite = true)
                    preRestoreActiveDbSnapshot = snapshot
                }

                val rollbackTargets =
                    (stagedFiles.keys + stagedCustomFonts.keys + databaseRollbackTargets).distinct()
                rollbackTargets.forEachIndexed { index, target ->
                    if (target.exists() && !target.name.endsWith("-shm")) {
                        val rollback = File(stagingDir, "rollback/$index")
                        rollback.parentFile?.mkdirs()
                        target.copyTo(rollback, overwrite = true)
                        rollbackFiles[target] = rollback
                    } else {
                        rollbackFiles[target] = null
                    }
                }
                databaseRollbackSnapshotsReady = true

                stagedCustomFonts.forEach { (target, staged) ->
                    target.parentFile?.mkdirs()
                    modifiedTargets.add(target)
                    staged.copyTo(target, overwrite = true)
                }

                stagedFiles.forEach { (target, staged) ->
                    target.parentFile?.mkdirs()
                    modifiedTargets.add(target)
                    staged.copyTo(target, overwrite = true)
                }
            }

            stagedModularSettings?.let { payload ->
                ModularBackupData.restoreSnapshot(
                    context = context,
                    bytes = payload,
                    selectedContents = contents,
                    backupInfo = info,
                )
            }

            if (restoreSuggestions && stagedSuggestionSettings != null) {
                restoreSuggestionSettings(context, requireNotNull(stagedSuggestionSettings))
                PreferenceManager2.getInstance(context).reloadFromDataStore()
            }

            if (restoreLauncherDb) {
                val prefManager = PreferenceManager.getInstance(context)
                prefManager.suppressListeners = true
                try {
                    prefManager.reloadFromStorage()
                    PreferenceManager2.getInstance(context).reloadFromDataStore()
                    val idp = InvariantDeviceProfile.INSTANCE.get(context)
                    idp.reset(context)
                    val targetDbName = idp.dbFile
                    val targetDbFile = context.getDatabasePath(targetDbName)
                    listOf("", "-wal", "-journal", "-shm").forEach { suffix ->
                        val file = File(targetDbFile.path + suffix)
                        if (file !in rollbackFiles && !suffix.endsWith("-shm")) {
                            if (file.exists()) {
                                val rollback = File(
                                    stagingDir,
                                    "rollback/target_${file.name}",
                                )
                                rollback.parentFile?.mkdirs()
                                file.copyTo(rollback, overwrite = true)
                                rollbackFiles[file] = rollback
                            } else {
                                rollbackFiles[file] = null
                            }
                        }
                        modifiedTargets.add(file)
                    }

                    val restoredDbTarget = launcherDbFile(context, forRestore = true)
                    withContext(Dispatchers.IO) {
                        ModularBackupData.filterLauncherDatabaseContainers(
                            databaseFile = restoredDbTarget,
                            keepHomeScreen = restoreHomeScreen,
                            keepDock = restoreDock,
                        )
                    }

                    DeviceGridState(idp).writeToPrefs(context, true)
                    LauncherPrefs.get(context).putSync(LauncherPrefs.DB_FILE.to(targetDbName))

                    val restoreController = ModelDbController(context, true)
                    val restoreSucceeded = try {
                        RestoreDbTask.performRestore(context, restoreController)
                    } finally {
                        restoreController.close()
                    }
                    check(restoreSucceeded) { "Launcher database restore failed" }

                    if ((restoreHomeScreen xor restoreDock) && preRestoreActiveDbSnapshot != null) {
                        withContext(Dispatchers.IO) {
                            ModularBackupData.mergePreservedLauncherContainers(
                                targetDbFile = targetDbFile,
                                preservedDbFile = requireNotNull(preRestoreActiveDbSnapshot),
                                copyHomeScreenFromPreserved = !restoreHomeScreen,
                                copyDockFromPreserved = !restoreDock,
                            )
                            checkpointLauncherDatabases(listOf(targetDbFile))
                        }
                    } else {
                        withContext(Dispatchers.IO) {
                            checkpointLauncherDatabases(listOf(targetDbFile))
                        }
                    }

                    LauncherPrefs.get(context).removeSync(LauncherPrefs.RESTORE_DEVICE)
                    prefManager.reloadFromStorage()
                } finally {
                    prefManager.suppressListeners = false
                }
            } else if (restoreHomeScreen || restoreDock) {
                val idp = InvariantDeviceProfile.INSTANCE.get(context)
                idp.reset(context)
            }

            if (restoreWallpaper) {
                WallpaperManager.getInstance(context).setBitmap(requireNotNull(stagedWallpaper))
            }
        } catch (throwable: Throwable) {
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
            preRestoreModularSnapshot?.let { snapshot ->
                runCatching {
                    ModularBackupData.restoreSnapshot(context, snapshot, ALL_CONTENTS_MASK)
                }.exceptionOrNull()?.let(throwable::addSuppressed)
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
        val snapshot = requireNotNull(archiveSnapshot?.takeIf(File::isFile)) {
            "Backup archive snapshot is unavailable"
        }
        withContext(Dispatchers.IO) {
            ZipInputStream(FileInputStream(snapshot)).use { zip ->
                var entry: ZipEntry? = null
                while (zip.nextEntry?.also { entry = it } != null) {
                    val currentEntry = entry ?: continue
                    val handler = handlers[currentEntry.name] ?: continue
                    try {
                        handler(zip)
                    } finally {
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    /**
     * Validates archive structure, entry names, and uncompressed sizes before extracting any
     * entry. Central-directory metadata is cross-checked with a full stream read so spoofed
     * headers cannot bypass size limits.
     */
    private suspend fun scanArchive(requiredEntries: Set<String>): Set<String> = withContext(Dispatchers.IO) {
            val snapshot = requireNotNull(archiveSnapshot?.takeIf(File::isFile)) {
                "Backup archive snapshot is unavailable"
            }
            validateCentralDirectory(snapshot)
            val seen = mutableSetOf<String>()
            var totalBytes = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val streamEntryNames = mutableListOf<String>()
            val streamEntrySizes = mutableMapOf<String, Long>()
            ZipInputStream(FileInputStream(snapshot)).use { stream ->
                while (true) {
                    val entry = stream.nextEntry ?: break
                    val name = entry.name
                    require(!entry.isDirectory && isAllowedEntry(name)) {
                        "Invalid backup entry: $name"
                    }
                    require(seen.add(name)) { "Duplicate backup entry: $name" }
                    require(seen.size <= MAX_ENTRY_COUNT) { "Too many backup entries" }
                    var entryBytes = 0L
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        entryBytes += count
                        totalBytes += count
                        require(
                            entryBytes <= MAX_ENTRY_BYTES && totalBytes <= MAX_ARCHIVE_BYTES,
                        ) { "Backup archive exceeds its declared or supported size" }
                    }
                    stream.closeEntry()
                    streamEntryNames.add(name)
                    streamEntrySizes[name] = entryBytes
                }
            }
            totalBytes = 0L
            seen.clear()
            ZipFile(snapshot).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    require(!entry.isDirectory && isAllowedEntry(entry.name)) {
                        "Invalid backup entry: ${entry.name}"
                    }
                    require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                    val declaredSize = entry.size
                    val compressedSize = entry.compressedSize
                    require(
                        declaredSize in 0..MAX_ENTRY_BYTES &&
                            compressedSize in 0..MAX_ARCHIVE_BYTES,
                    ) { "Backup entry size is invalid or unsupported: ${entry.name}" }
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
            require(
                streamEntryNames.size == seen.size &&
                    streamEntryNames.toSet() == seen &&
                    streamEntrySizes.all { (name, size) ->
                        ZipFile(snapshot).use { zip -> zip.getEntry(name)?.size == size }
                    },
            ) { "ZIP local headers do not match central-directory metadata" }
            require(seen.containsAll(requiredEntries)) {
                "Backup is missing required entries: ${requiredEntries - seen}"
            }
            seen
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

    private fun readUInt16LittleEndian(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xff) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readUInt32LittleEndian(bytes: ByteArray, offset: Int): Long = (bytes[offset].toLong() and 0xff) or
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

    private fun validateLauncherDatabase(file: File) {
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        database.use { db ->
            db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "Launcher database failed integrity validation"
                }
            }
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='favorites'",
                null,
            ).use { cursor ->
                require(cursor.moveToFirst()) { "Launcher database has no favorites table" }
            }
        }
    }

    private fun validateInfo(backupInfo: BackupInfo) {
        require(backupInfo.backupVersion == BACKUP_VERSION) {
            "Unsupported backup version: ${backupInfo.backupVersion}"
        }
        val contents = backupInfo.contents
        require(contents != 0 && (contents and KNOWN_CONTENTS.inv()) == 0) {
            "Invalid backup contents"
        }
        require(backupInfo.previewWidth >= 0 && backupInfo.previewHeight >= 0) {
            "Invalid preview dimensions"
        }
    }

    private fun isAllowedEntry(name: String): Boolean = (
        name in setOf(
            INFO_FILE_NAME,
            SCREENSHOT_FILE_NAME,
            WALLPAPER_FILE_NAME,
            LAUNCHER_DB_FILE_NAME,
            SUGGESTION_SETTINGS_FILE_NAME,
            MODULAR_SETTINGS_FILE_NAME,
            PREFS_FILE_NAME,
            PREFS_DB_FILE_NAME,
            PREFS_DATASTORE_FILE_NAME,
        ) || isWallpaperLibraryEntry(name) || isCustomFontEntry(name)
        ) && !name.contains("..") && !name.startsWith("/")

    private fun isWallpaperLibraryEntry(name: String): Boolean {
        if (!name.startsWith(WALLPAPER_LIBRARY_ENTRY_PREFIX)) return false
        val fileName = name.removePrefix(WALLPAPER_LIBRARY_ENTRY_PREFIX)
        return fileName.isNotBlank() && File(fileName).name == fileName && !fileName.contains("..")
    }

    private fun isCustomFontEntry(name: String): Boolean {
        if (!name.startsWith(CUSTOM_FONT_ENTRY_PREFIX)) return false
        val fileName = name.removePrefix(CUSTOM_FONT_ENTRY_PREFIX)
        return fileName.isNotBlank() && File(fileName).name == fileName && !fileName.contains("..")
    }

    companion object {
        private const val BACKUP_VERSION = 1
        private const val MAX_PREVIEW_SIZE = 4000
        private const val MAX_IMAGE_DIMENSION = 32768
        private const val MAX_IMAGE_PIXELS = 16L * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 64 * 1024 * 1024
        private const val MAX_ENTRY_COUNT = 256
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
        private const val MAX_SUGGESTION_SETTINGS_BYTES = 64 * 1024

        private const val PREFS_FILE_NAME = "${LauncherFiles.SHARED_PREFERENCES_KEY}.xml"
        private const val PREFS_DB_FILE_NAME = "preferences"
        private const val PREFS_DATASTORE_FILE_NAME = "preferences.preferences_pb"

        const val INFO_FILE_NAME = "info.pb"
        const val WALLPAPER_FILE_NAME = "wallpaper.png"
        const val SCREENSHOT_FILE_NAME = "screenshot.png"
        const val SUGGESTION_SETTINGS_FILE_NAME = "suggestion-settings.json"
        const val MODULAR_SETTINGS_FILE_NAME = "modular-settings.json"
        private const val WALLPAPER_LIBRARY_ENTRY_PREFIX = "wallpapers/"
        private const val CUSTOM_FONT_ENTRY_PREFIX = "custom_fonts/"
        const val LAUNCHER_DB_FILE_NAME = "launcher.db"
        const val RESTORED_DB_FILE_NAME = "restored.db"

        const val INCLUDE_LAYOUT_AND_SETTINGS = 1 shl 0
        const val INCLUDE_WALLPAPER = 1 shl 1
        const val INCLUDE_SUGGESTION_SETTINGS = 1 shl 2
        const val INCLUDE_DOCK = 1 shl 3
        const val INCLUDE_APP_DRAWER = 1 shl 4
        const val INCLUDE_SEARCH = 1 shl 5
        const val INCLUDE_FOLDERS = 1 shl 6
        const val INCLUDE_THEME_AND_FONTS = 1 shl 7
        const val INCLUDE_ICONS = 1 shl 8
        const val INCLUDE_GESTURES_AND_EXTRAS = 1 shl 9

        private const val GRANULAR_MODULE_FLAGS =
            INCLUDE_DOCK or
                INCLUDE_APP_DRAWER or
                INCLUDE_SEARCH or
                INCLUDE_FOLDERS or
                INCLUDE_THEME_AND_FONTS or
                INCLUDE_ICONS or
                INCLUDE_GESTURES_AND_EXTRAS

        const val NON_WALLPAPER_CONTENTS =
            INCLUDE_LAYOUT_AND_SETTINGS or
                INCLUDE_SUGGESTION_SETTINGS or
                GRANULAR_MODULE_FLAGS

        const val ALL_CONTENTS_MASK = NON_WALLPAPER_CONTENTS or INCLUDE_WALLPAPER
        private const val KNOWN_CONTENTS = ALL_CONTENTS_MASK

        const val MIME_TYPE = "application/zip"
        val EXTRA_MIME_TYPES = arrayOf(MIME_TYPE, "application/x-zip", "application/octet-stream")

        val contentOptions = listOf(
            INCLUDE_LAYOUT_AND_SETTINGS to R.string.backup_content_layout_and_settings,
            INCLUDE_DOCK to R.string.backup_content_dock,
            INCLUDE_APP_DRAWER to R.string.backup_content_app_drawer,
            INCLUDE_SUGGESTION_SETTINGS to R.string.backup_content_suggestion_settings,
            INCLUDE_SEARCH to R.string.backup_content_search,
            INCLUDE_FOLDERS to R.string.backup_content_folders,
            INCLUDE_THEME_AND_FONTS to R.string.backup_content_theme_and_fonts,
            INCLUDE_ICONS to R.string.backup_content_icons,
            INCLUDE_GESTURES_AND_EXTRAS to R.string.backup_content_gestures_and_extras,
            INCLUDE_WALLPAPER to R.string.backup_content_wallpaper,
        )

        fun generateBackupFileName(): String {
            val fileName = "Solina_Backup_${SimpleDateFormat.getDateTimeInstance().format(Date())}"
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

        @SuppressLint("MissingPermission")
        suspend fun create(
            context: Context,
            contents: Int,
            screenshotBitmap: Bitmap,
            fileUri: Uri,
        ) {
            require(contents != 0 && (contents and KNOWN_CONTENTS.inv()) == 0) {
                "Invalid backup contents"
            }
            val idp = LauncherAppState.getIDP(context)
            val createdAt = Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000)
            val colorHints =
                WallpaperManagerCompat.INSTANCE.get(context).wallpaperColors?.colorHints ?: 0
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

            val includeHomeScreen = contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)
            val includeDock = contents.hasFlag(INCLUDE_DOCK)
            val includeLauncherDb = includeHomeScreen || includeDock
            val includeAnyNonWallpaper = (contents and NON_WALLPAPER_CONTENTS) != 0

            if (includeAnyNonWallpaper || contents.hasFlag(INCLUDE_WALLPAPER)) {
                AppDatabase.INSTANCE.get(context).checkpoint()
            }

            val wallpaperLibraryFiles =
                if (contents.hasFlag(INCLUDE_WALLPAPER) || includeHomeScreen) {
                    File(context.filesDir, "wallpapers").listFiles()
                        ?.filter {
                            it.isFile && File(it.name).name == it.name && !it.name.contains("..")
                        }
                        ?: emptyList()
                } else {
                    emptyList()
                }

            val customFontFiles = if (contents.hasFlag(INCLUDE_THEME_AND_FONTS)) {
                FontCache.TTFFont.getFontsDir(context).listFiles()
                    ?.filter {
                        it.isFile && File(it.name).name == it.name && !it.name.contains("..")
                    }
                    ?: emptyList()
            } else {
                emptyList()
            }

            val modularSettingsSnapshot =
                if (includeAnyNonWallpaper || contents.hasFlag(INCLUDE_WALLPAPER)) {
                    ModularBackupData.exportSnapshot(context, contents)
                } else {
                    null
                }

            val suggestionSettingsSnapshot = if (contents.hasFlag(INCLUDE_SUGGESTION_SETTINGS)) {
                createSuggestionSettingsSnapshot(context)
            } else {
                null
            }

            val wallpaperBitmap = if (contents.hasFlag(INCLUDE_WALLPAPER)) {
                val wallpaperManager = WallpaperManager.getInstance(context)
                wallpaperManager.drawable?.toBitmap()
                    ?: throw IllegalStateException("Unable to read current wallpaper")
            } else {
                null
            }

            val tempFilteredDb = if (includeLauncherDb) {
                File(context.cacheDir, "backup_launcher_${System.nanoTime()}.db")
            } else {
                null
            }

            try {
                withContext(Dispatchers.IO) {
                    if (includeLauncherDb) {
                        val activeDb = launcherDbFile(context, forRestore = false)
                        require(activeDb.isFile && activeDb.length() > 0L) {
                            "Launcher database is not available for backup"
                        }
                        checkpointLauncherDatabases(listOf(activeDb))
                        val tempDb = requireNotNull(tempFilteredDb)
                        activeDb.copyTo(tempDb, overwrite = true)
                        ModularBackupData.filterLauncherDatabaseContainers(
                            databaseFile = tempDb,
                            keepHomeScreen = includeHomeScreen,
                            keepDock = includeDock,
                        )
                        checkpointLauncherDatabases(listOf(tempDb))
                    }

                    val legacySourceFiles = if (includeHomeScreen) {
                        getFiles(context, forRestore = false).filterKeys {
                            it != LAUNCHER_DB_FILE_NAME
                        }
                    } else {
                        emptyMap()
                    }

                    val allFilesToVerify = buildList {
                        tempFilteredDb?.let(::add)
                        addAll(legacySourceFiles.values.filter(File::isFile))
                        addAll(wallpaperLibraryFiles)
                        addAll(customFontFiles)
                    }
                    require(allFilesToVerify.all { it.length() <= MAX_ENTRY_BYTES }) {
                        "A backup source file exceeds the supported size"
                    }
                    require(allFilesToVerify.sumOf { it.length() } <= MAX_ARCHIVE_BYTES) {
                        "Backup sources exceed the supported size"
                    }

                    val pfd = context.contentResolver.openFileDescriptor(fileUri, "wt")
                        ?: throw IllegalStateException("Unable to open backup destination")
                    pfd.use {
                        FileOutputStream(pfd.fileDescriptor).use { fileOut ->
                            ZipOutputStream(fileOut.buffered()).use { out ->
                                out.putNextEntry(ZipEntry(INFO_FILE_NAME))
                                info.writeTo(out)
                                out.closeEntry()

                                if (contents.hasFlag(INCLUDE_WALLPAPER)) {
                                    out.putNextEntry(ZipEntry(WALLPAPER_FILE_NAME))
                                    require(
                                        requireNotNull(wallpaperBitmap).compress(
                                            Bitmap.CompressFormat.PNG,
                                            100,
                                            out,
                                        ),
                                    ) { "Unable to encode wallpaper" }
                                    out.closeEntry()
                                }

                                modularSettingsSnapshot?.let { payload ->
                                    out.putNextEntry(ZipEntry(MODULAR_SETTINGS_FILE_NAME))
                                    out.write(payload)
                                    out.closeEntry()
                                }

                                suggestionSettingsSnapshot?.let { payload ->
                                    out.putNextEntry(ZipEntry(SUGGESTION_SETTINGS_FILE_NAME))
                                    out.write(payload)
                                    out.closeEntry()
                                }

                                if (includeAnyNonWallpaper) {
                                    out.putNextEntry(ZipEntry(SCREENSHOT_FILE_NAME))
                                    require(
                                        screenshotBitmap.compress(
                                            Bitmap.CompressFormat.PNG,
                                            85,
                                            out,
                                        ),
                                    ) { "Unable to encode backup preview" }
                                    out.closeEntry()
                                }

                                tempFilteredDb?.let { dbFile ->
                                    out.putNextEntry(ZipEntry(LAUNCHER_DB_FILE_NAME))
                                    dbFile.inputStream().use { input -> input.copyTo(out) }
                                    out.closeEntry()
                                }

                                legacySourceFiles.forEach { (entryName, file) ->
                                    if (!file.isFile) return@forEach
                                    out.putNextEntry(ZipEntry(entryName))
                                    file.inputStream().use { input -> input.copyTo(out) }
                                    out.closeEntry()
                                }

                                wallpaperLibraryFiles.forEach { wallpaperFile ->
                                    out.putNextEntry(
                                        ZipEntry(WALLPAPER_LIBRARY_ENTRY_PREFIX + wallpaperFile.name),
                                    )
                                    wallpaperFile.inputStream().use { input -> input.copyTo(out) }
                                    out.closeEntry()
                                }

                                customFontFiles.forEach { fontFile ->
                                    out.putNextEntry(
                                        ZipEntry(CUSTOM_FONT_ENTRY_PREFIX + fontFile.name),
                                    )
                                    fontFile.inputStream().use { input -> input.copyTo(out) }
                                    out.closeEntry()
                                }

                                out.finish()
                            }
                            fileOut.fd.sync()
                        }
                    }
                }
            } finally {
                tempFilteredDb?.delete()
                wallpaperBitmap?.recycle()
            }
        }

        private suspend fun createSuggestionSettingsSnapshot(context: Context): ByteArray {
            val preferences = PreferenceManager2.getInstance(context).preferencesDataStore.data.first()
            val json = JSONObject().apply {
                put("version", 1)
                put(
                    "show_suggested_apps_at_drawer_top",
                    preferences[booleanPreferencesKey("show_suggested_apps_at_drawer_top")] ?: true,
                )
                put(
                    "suggestion_rows",
                    preferences[intPreferencesKey("suggestion_rows")] ?: 1,
                )
                put(
                    "suggestion_position",
                    preferences[stringPreferencesKey("suggestion_position")] ?: "top",
                )
                put(
                    "suggestion_source",
                    preferences[stringPreferencesKey("suggestion_source")] ?: "usage",
                )
                put(
                    "suggestion_icon_scale",
                    preferences[floatPreferencesKey("suggestion_icon_scale")] ?: 1f,
                )
                put(
                    "suggestion_label_scale",
                    preferences[floatPreferencesKey("suggestion_label_scale")] ?: 1f,
                )
                putNullable(
                    "suggestion_label_color",
                    preferences[stringPreferencesKey("suggestion_label_color")],
                )
                put(
                    "suggestion_labels_visible",
                    preferences[booleanPreferencesKey("suggestion_labels_visible")] ?: true,
                )
                put(
                    "suggestion_background_enabled",
                    preferences[booleanPreferencesKey("suggestion_background_enabled")] ?: true,
                )
                putNullable(
                    "suggestion_background_color",
                    preferences[stringPreferencesKey("suggestion_background_color")],
                )
                put(
                    "suggestion_background_alpha",
                    preferences[floatPreferencesKey("suggestion_background_alpha")] ?: 0.20f,
                )
                put(
                    "suggestion_corner_radius",
                    preferences[floatPreferencesKey("suggestion_corner_radius")] ?: 16f,
                )
                put(
                    "suggestion_padding",
                    preferences[floatPreferencesKey("suggestion_padding")] ?: 8f,
                )
                put(
                    "suggestion_margin",
                    preferences[floatPreferencesKey("suggestion_margin")] ?: 4f,
                )
                put(
                    "suggestion_row_height_factor",
                    preferences[floatPreferencesKey("suggestion_row_height_factor")] ?: 1f,
                )
                put(
                    "suggestion_label_vertical_offset",
                    preferences[floatPreferencesKey("suggestion_label_vertical_offset")] ?: 0f,
                )
            }
            return json.toString().toByteArray(Charsets.UTF_8)
        }

        private fun JSONObject.putNullable(name: String, value: String?) {
            put(name, value ?: JSONObject.NULL)
        }

        private fun validateSuggestionSettings(payload: ByteArray) {
            val json = JSONObject(payload.toString(Charsets.UTF_8))
            require(json.getInt("version") == 1) { "Unsupported suggestion settings version" }
            require(json.getInt("suggestion_rows") in 1..3) { "Invalid suggestion row count" }
            require(json.getString("suggestion_position") in setOf("top", "bottom")) {
                "Invalid suggestion position"
            }
            require(json.getString("suggestion_source") in setOf("usage", "dock")) {
                "Invalid suggestion source"
            }
            require(json.getDouble("suggestion_icon_scale") in 0.5..1.5) {
                "Invalid suggestion icon scale"
            }
            require(json.getDouble("suggestion_label_scale") in 0.5..1.5) {
                "Invalid suggestion label scale"
            }
            require(json.getDouble("suggestion_background_alpha") in 0.0..1.0) {
                "Invalid suggestion background opacity"
            }
            require(json.getDouble("suggestion_corner_radius") in 0.0..32.0) {
                "Invalid suggestion corner radius"
            }
            require(json.getDouble("suggestion_padding") in 0.0..24.0) {
                "Invalid suggestion padding"
            }
            require(json.getDouble("suggestion_margin") in 0.0..24.0) {
                "Invalid suggestion margin"
            }
            require(json.optDouble("suggestion_row_height_factor", 1.0) in 0.3..1.5) {
                "Invalid suggestion row height"
            }
            require(json.optDouble("suggestion_label_vertical_offset", 0.0) in -16.0..16.0) {
                "Invalid suggestion label offset"
            }
        }

        private suspend fun restoreSuggestionSettings(context: Context, payload: ByteArray) {
            validateSuggestionSettings(payload)
            val json = JSONObject(payload.toString(Charsets.UTF_8))
            val preferencesDataStore = PreferenceManager2.getInstance(context).preferencesDataStore
            preferencesDataStore.edit { preferences ->
                preferences[booleanPreferencesKey("show_suggested_apps_at_drawer_top")] =
                    json.getBoolean("show_suggested_apps_at_drawer_top")
                preferences[intPreferencesKey("suggestion_rows")] = json.getInt("suggestion_rows")
                val positionKey = stringPreferencesKey("suggestion_position")
                preferences[positionKey] = json.getString("suggestion_position")
                val sourceKey = stringPreferencesKey("suggestion_source")
                preferences[sourceKey] = json.getString("suggestion_source")
                preferences[floatPreferencesKey("suggestion_icon_scale")] =
                    json.getDouble("suggestion_icon_scale").toFloat()
                preferences[floatPreferencesKey("suggestion_label_scale")] =
                    json.getDouble("suggestion_label_scale").toFloat()
                preferences[booleanPreferencesKey("suggestion_labels_visible")] =
                    json.getBoolean("suggestion_labels_visible")
                preferences[booleanPreferencesKey("suggestion_background_enabled")] =
                    json.getBoolean("suggestion_background_enabled")
                preferences[floatPreferencesKey("suggestion_background_alpha")] =
                    json.getDouble("suggestion_background_alpha").toFloat()
                preferences[floatPreferencesKey("suggestion_corner_radius")] =
                    json.getDouble("suggestion_corner_radius").toFloat()
                preferences[floatPreferencesKey("suggestion_padding")] =
                    json.getDouble("suggestion_padding").toFloat()
                preferences[floatPreferencesKey("suggestion_margin")] =
                    json.getDouble("suggestion_margin").toFloat()
                preferences[floatPreferencesKey("suggestion_row_height_factor")] =
                    json.optDouble("suggestion_row_height_factor", 1.0).toFloat()
                preferences[floatPreferencesKey("suggestion_label_vertical_offset")] =
                    json.optDouble("suggestion_label_vertical_offset", 0.0).toFloat()

                val labelColorKey = stringPreferencesKey("suggestion_label_color")
                if (json.isNull("suggestion_label_color")) {
                    preferences.remove(labelColorKey)
                } else {
                    preferences[labelColorKey] = json.getString("suggestion_label_color")
                }

                val backgroundColorKey = stringPreferencesKey("suggestion_background_color")
                if (json.isNull("suggestion_background_color")) {
                    preferences.remove(backgroundColorKey)
                } else {
                    preferences[backgroundColorKey] = json.getString("suggestion_background_color")
                }
            }
        }

        private fun launcherDbFile(context: Context, forRestore: Boolean): File {
            val dbName =
                if (forRestore) RESTORED_DB_FILE_NAME else LauncherAppState.getIDP(context).dbFile
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
