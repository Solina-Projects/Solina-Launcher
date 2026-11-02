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
import app.lawnchair.font.FontCache
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
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
        var tmpScreenshot: Bitmap? = null
        var tmpWallpaper: Bitmap? = null
        readZip(
            mapOf(
                INFO_FILE_NAME to { info = BackupInfo.newBuilder().mergeFrom(it).build() },
                SCREENSHOT_FILE_NAME to { tmpScreenshot = decodeBoundedBitmap(it, MAX_SCREENSHOT_BYTES) },
                WALLPAPER_FILE_NAME to { tmpWallpaper = decodeBoundedBitmap(it, MAX_WALLPAPER_BYTES) },
            ),
        )
        require(info.backupVersion in 1..BACKUP_VERSION) { "Unsupported backup version" }
        require(info.contents != 0) { "Backup contains no restorable sections" }
        val size = max(info.previewWidth, info.previewHeight).coerceAtMost(4000)
        screenshot = tmpScreenshot?.scaleDownTo(size)
        wallpaper = tmpWallpaper?.scaleDownToDisplaySize(context)
    }

    suspend fun restore(selectedContents: Int) {
        val contents = selectedContents and info.contents
        val handlers = mutableMapOf<String, suspend (InputStream) -> Unit>()
        if (info.backupVersion < MODULAR_BACKUP_VERSION && contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
            // Backward compatibility for v1 archives, which stored all preference files together.
            handlers.putAll(
                getFiles(context, forRestore = true).mapValues { entry ->
                    {
                        val file = entry.value
                        file.parentFile?.mkdirs()
                        file.outputStream().use { out -> it.copyTo(out) }
                    }
                },
            )
        } else if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
            val database = launcherDbFile(context, forRestore = true)
            handlers[LAUNCHER_DB_FILE_NAME] = {
                database.parentFile?.mkdirs()
                database.outputStream().use { out -> it.copyTo(out) }
            }
            handlers[LAUNCHER_DB_WAL_FILE_NAME] = {
                File(database.path + "-wal").outputStream().use { out -> it.copyTo(out) }
            }
        }

        val selectedModules = modulesForContents(contents)
        if (selectedModules.isNotEmpty()) {
            handlers[MODULE_PREFERENCES_FILE_NAME] = {
                ModularPreferenceBackup.restore(context, it.readBytes(), selectedModules)
            }
        }
        if (contents.hasFlag(INCLUDE_WALLPAPER)) {
            handlers[WALLPAPER_FILE_NAME] = {
                val bitmap = decodeBoundedBitmap(it, MAX_WALLPAPER_BYTES)
                    ?: throw IllegalArgumentException("Invalid wallpaper image")
                WallpaperManager.getInstance(context).setBitmap(bitmap)
            }
        }

        // Validate every entry, CRC, selected snapshot and image before mutating user data.
        val validationHandlers = mutableMapOf<String, suspend (InputStream) -> Unit>()
        if (info.backupVersion >= MODULAR_BACKUP_VERSION && selectedModules.isNotEmpty()) {
            validationHandlers[MODULE_PREFERENCES_FILE_NAME] = {
                ModularPreferenceBackup.validate(it.readBytes())
            }
        }
        if (contents.hasFlag(INCLUDE_WALLPAPER)) {
            validationHandlers[WALLPAPER_FILE_NAME] = {
                decodeBoundedBitmap(it, MAX_WALLPAPER_BYTES)
            }
        }
        val archiveEntries = readZip(
            validationHandlers,
            validateCustomFonts = "typography" in selectedModules,
        )
        if (info.backupVersion >= MODULAR_BACKUP_VERSION && selectedModules.isNotEmpty()) {
            require(MODULE_PREFERENCES_FILE_NAME in archiveEntries) {
                "Backup is missing its modular preferences snapshot"
            }
        }
        if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
            require(LAUNCHER_DB_FILE_NAME in archiveEntries) {
                "Backup does not contain a launcher database"
            }
            val staleRestoreDatabase = launcherDbFile(context, forRestore = true)
            staleRestoreDatabase.delete()
            File(staleRestoreDatabase.path + "-wal").delete()
            File(staleRestoreDatabase.path + "-shm").delete()
            File(staleRestoreDatabase.path + "-journal").delete()
        }
        if (contents.hasFlag(INCLUDE_WALLPAPER)) {
            require(WALLPAPER_FILE_NAME in archiveEntries) { "Backup does not contain a wallpaper image" }
        }
        readZip(handlers, restoreCustomFonts = "typography" in selectedModules)
        if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
            val restoredDatabase = launcherDbFile(context, forRestore = true)
            require(restoredDatabase.isFile && restoredDatabase.length() > 0L) {
                "Backup does not contain a valid launcher database"
            }
            validateRestoredDatabase(restoredDatabase)
            // Keep the old database intact until the replacement has been fully extracted.
            deleteActiveLauncherDatabase(context)
            DeviceGridState(info.gridState).writeToPrefs(context, true)
            check(RestoreDbTask.performRestore(context, ModelDbController(context))) {
                "Launcher database restore failed"
            }
        }
    }

    private suspend fun readZip(
        handlers: Map<String, suspend (InputStream) -> Unit>,
        restoreCustomFonts: Boolean = false,
        validateCustomFonts: Boolean = false,
    ): Set<String> = withContext(Dispatchers.IO) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Unable to open backup file")
        pfd.use {
            FileInputStream(it.fileDescriptor).use { inStream ->
                ZipInputStream(inStream).use { zipIs ->
                    var entryCount = 0
                    var customFontCount = 0
                    var totalBytes = 0L
                    val seenEntries = mutableSetOf<String>()
                    while (true) {
                        val entry = zipIs.nextEntry ?: break
                        require(seenEntries.add(entry.name)) { "Backup contains duplicate entries" }
                        if (entry.name.startsWith(CUSTOM_FONT_ENTRY_PREFIX)) {
                            customFontCount++
                            require(customFontCount <= MAX_CUSTOM_FONT_FILES) {
                                "Backup contains too many custom fonts"
                            }
                        }
                        entryCount++
                        require(entryCount <= MAX_ARCHIVE_ENTRIES) { "Backup contains too many entries" }
                        val limit = maxEntryBytes(entry.name)
                        require(entry.size < 0L || entry.size <= limit) { "Backup entry is too large: ${entry.name}" }
                        val bounded = EntryLimitedInputStream(zipIs, limit)
                        val handler = handlers[entry.name] ?: if (
                            (restoreCustomFonts || validateCustomFonts) &&
                            entry.name.startsWith(CUSTOM_FONT_ENTRY_PREFIX)
                        ) {
                            { input: InputStream ->
                                val fontName = entry.name.removePrefix(CUSTOM_FONT_ENTRY_PREFIX)
                                if (restoreCustomFonts) {
                                    restoreCustomFont(fontName, input)
                                } else {
                                    validateCustomFont(fontName, input)
                                }
                            }
                        } else {
                            null
                        }
                        handler?.invoke(bounded)
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (bounded.read(buffer) != -1) { }
                        if (entry.name == LAUNCHER_DB_FILE_NAME) {
                            require(bounded.bytesRead > 0L) { "Launcher database entry is empty" }
                        }
                        totalBytes += bounded.bytesRead
                        require(totalBytes <= MAX_ARCHIVE_UNCOMPRESSED_BYTES) {
                            "Backup expands beyond the allowed size"
                        }
                        zipIs.closeEntry()
                    }
                    seenEntries
                }
            }
        }
    }

    private class EntryLimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        var bytesRead: Long = 0
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) count(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) count(read)
            return read
        }

        private fun count(amount: Int) {
            bytesRead += amount
            require(bytesRead <= limit) { "Backup entry exceeds its size limit" }
        }

        override fun close() = Unit
    }

    private fun maxEntryBytes(name: String): Long = when {
        name.startsWith(CUSTOM_FONT_ENTRY_PREFIX) -> MAX_CUSTOM_FONT_BYTES
        name == MODULE_PREFERENCES_FILE_NAME -> MAX_PREFERENCES_BYTES
        name == WALLPAPER_FILE_NAME -> MAX_WALLPAPER_BYTES
        name == SCREENSHOT_FILE_NAME -> MAX_SCREENSHOT_BYTES
        name == LAUNCHER_DB_FILE_NAME || name == LAUNCHER_DB_WAL_FILE_NAME -> MAX_DATABASE_BYTES
        name == INFO_FILE_NAME -> MAX_INFO_BYTES
        else -> MAX_UNKNOWN_ENTRY_BYTES
    }

    private fun validateRestoredDatabase(file: File) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "Launcher database integrity check failed"
                }
            }
            db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='favorites'", null).use { cursor ->
                require(cursor.moveToFirst()) { "Launcher database is missing its favorites table" }
            }
        }
    }

    private fun validateCustomFont(entryName: String, input: InputStream) {
        validateFontEntryName(entryName)
        val temporary = File.createTempFile("solina-font-check-", ".tmp", context.cacheDir)
        try {
            temporary.outputStream().use { output -> input.copyTo(output) }
            require(FontCache.TTFFont.createTypeface(temporary) != null) { "Invalid custom font file" }
        } finally {
            temporary.delete()
        }
    }

    private fun restoreCustomFont(entryName: String, input: InputStream) {
        validateFontEntryName(entryName)
        val directory = FontCache.TTFFont.getFontsDir(context)
        val target = File(directory, entryName)
        val temporary = File(directory, "$entryName.restore.tmp")
        val previous = File(directory, "$entryName.restore.bak")
        temporary.outputStream().use { output -> input.copyTo(output) }
        require(FontCache.TTFFont.createTypeface(temporary) != null) { "Invalid custom font file" }
        if (previous.exists()) previous.delete()
        if (target.exists()) require(target.renameTo(previous)) { "Unable to stage existing custom font" }
        if (!temporary.renameTo(target)) {
            previous.takeIf { it.exists() }?.renameTo(target)
            throw IllegalStateException("Unable to install custom font")
        }
        previous.delete()
    }

    private fun validateFontEntryName(entryName: String) {
        require(
            entryName.isNotBlank() && File(entryName).name == entryName &&
                !entryName.contains('/') && !entryName.contains('\\') &&
                !entryName.contains(".."),
        ) { "Invalid custom font path" }
    }

    private fun decodeBoundedBitmap(input: InputStream, maxBytes: Long): Bitmap? {
        val bytes = input.readLimited(maxBytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image in backup" }
        require(bounds.outWidth <= MAX_IMAGE_DIMENSION && bounds.outHeight <= MAX_IMAGE_DIMENSION) {
            "Backup image dimensions are too large"
        }
        require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_IMAGE_PIXELS) {
            "Backup image pixel count is too large"
        }
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
            "Unable to decode image in backup"
        }
    }

    private fun InputStream.readLimited(maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Backup item exceeds its size limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        private const val BACKUP_VERSION = 2
        private const val MODULAR_BACKUP_VERSION = 2
        private const val PREFS_FILE_NAME = "${LauncherFiles.SHARED_PREFERENCES_KEY}.xml"
        private const val PREFS_DB_FILE_NAME = "preferences"
        private const val PREFS_DATASTORE_FILE_NAME = "preferences.preferences_pb"

        const val INFO_FILE_NAME = "info.pb"
        const val WALLPAPER_FILE_NAME = "wallpaper.png"
        const val SCREENSHOT_FILE_NAME = "screenshot.png"
        const val MODULE_PREFERENCES_FILE_NAME = "preferences.modules.json"
        private const val CUSTOM_FONT_ENTRY_PREFIX = "custom_fonts/"
        const val LAUNCHER_DB_FILE_NAME = "launcher.db"
        const val LAUNCHER_DB_WAL_FILE_NAME = "launcher.db-wal"
        const val RESTORED_DB_FILE_NAME = "restored.db"

        // Bit 0 retains the legacy layout/settings flag; bit 1 remains wallpaper.
        const val INCLUDE_LAYOUT_AND_SETTINGS = 1 shl 0
        const val INCLUDE_WALLPAPER = 1 shl 1
        const val INCLUDE_DOCK = 1 shl 2
        const val INCLUDE_APP_DRAWER = 1 shl 3
        const val INCLUDE_FOLDERS = 1 shl 4
        const val INCLUDE_ICONS = 1 shl 5
        const val INCLUDE_THEME = 1 shl 6
        const val INCLUDE_TYPOGRAPHY = 1 shl 7
        const val INCLUDE_WIDGETS = 1 shl 8
        const val INCLUDE_GESTURES = 1 shl 9
        const val INCLUDE_SEARCH = 1 shl 10
        const val INCLUDE_SMARTSPACE = 1 shl 11
        const val INCLUDE_TASKBAR = 1 shl 12
        const val INCLUDE_ANIMATIONS = 1 shl 13
        const val INCLUDE_NOTIFICATIONS = 1 shl 14
        const val INCLUDE_GENERAL = 1 shl 15
        const val INCLUDE_APP_OVERRIDES = 1 shl 16
        const val INCLUDE_OTHER = 1 shl 17

        private val MODULE_FLAGS = linkedMapOf(
            INCLUDE_LAYOUT_AND_SETTINGS to "home_screen",
            INCLUDE_DOCK to "dock",
            INCLUDE_APP_DRAWER to "app_drawer",
            INCLUDE_FOLDERS to "folders",
            INCLUDE_ICONS to "icons",
            INCLUDE_THEME to "theme",
            INCLUDE_TYPOGRAPHY to "typography",
            INCLUDE_WALLPAPER to "wallpaper",
            INCLUDE_WIDGETS to "widgets",
            INCLUDE_GESTURES to "gestures",
            INCLUDE_SEARCH to "search",
            INCLUDE_SMARTSPACE to "smartspace",
            INCLUDE_TASKBAR to "taskbar",
            INCLUDE_ANIMATIONS to "animations",
            INCLUDE_NOTIFICATIONS to "notifications",
            INCLUDE_GENERAL to "general",
            INCLUDE_APP_OVERRIDES to "app_overrides",
            INCLUDE_OTHER to "other",
        )

        private fun modulesForContents(contents: Int): Set<String> = MODULE_FLAGS.filterKeys { flag -> contents.hasFlag(flag) }.values.toSet()

        private const val MAX_ARCHIVE_ENTRIES = 128
        private const val MAX_ARCHIVE_UNCOMPRESSED_BYTES = 512L * 1024 * 1024
        private const val MAX_DATABASE_BYTES = 256L * 1024 * 1024
        private const val MAX_PREFERENCES_BYTES = 16L * 1024 * 1024
        private const val MAX_WALLPAPER_BYTES = 64L * 1024 * 1024
        private const val MAX_CUSTOM_FONT_BYTES = 64L * 1024 * 1024
        private const val MAX_CUSTOM_FONT_FILES = 32
        private const val MAX_SCREENSHOT_BYTES = 32L * 1024 * 1024
        private const val MAX_INFO_BYTES = 1024L * 1024
        private const val MAX_UNKNOWN_ENTRY_BYTES = 8L * 1024 * 1024
        private const val MAX_IMAGE_DIMENSION = 16_384
        private const val MAX_IMAGE_PIXELS = 40_000_000L

        const val MIME_TYPE = "application/zip"
        val EXTRA_MIME_TYPES = arrayOf(MIME_TYPE, "application/x-zip", "application/octet-stream")

        val contentOptions = listOf(
            INCLUDE_LAYOUT_AND_SETTINGS to R.string.backup_module_home_screen,
            INCLUDE_DOCK to R.string.backup_module_dock,
            INCLUDE_APP_DRAWER to R.string.backup_module_app_drawer,
            INCLUDE_FOLDERS to R.string.backup_module_folders,
            INCLUDE_ICONS to R.string.backup_module_icons,
            INCLUDE_THEME to R.string.backup_module_theme,
            INCLUDE_TYPOGRAPHY to R.string.backup_module_typography,
            INCLUDE_WALLPAPER to R.string.backup_module_wallpaper,
            INCLUDE_WIDGETS to R.string.backup_module_widgets,
            INCLUDE_GESTURES to R.string.backup_module_gestures,
            INCLUDE_SEARCH to R.string.backup_module_search,
            INCLUDE_SMARTSPACE to R.string.backup_module_smartspace,
            INCLUDE_TASKBAR to R.string.backup_module_taskbar,
            INCLUDE_ANIMATIONS to R.string.backup_module_animations,
            INCLUDE_NOTIFICATIONS to R.string.backup_module_notifications,
            INCLUDE_GENERAL to R.string.backup_module_general,
            INCLUDE_APP_OVERRIDES to R.string.backup_module_app_overrides,
            INCLUDE_OTHER to R.string.backup_module_other,
        )

        const val ALL_MODULES_MASK = INCLUDE_LAYOUT_AND_SETTINGS or INCLUDE_DOCK or INCLUDE_APP_DRAWER or
            INCLUDE_FOLDERS or INCLUDE_ICONS or INCLUDE_THEME or INCLUDE_TYPOGRAPHY or INCLUDE_WALLPAPER or
            INCLUDE_WIDGETS or INCLUDE_GESTURES or INCLUDE_SEARCH or INCLUDE_SMARTSPACE or INCLUDE_TASKBAR or
            INCLUDE_ANIMATIONS or INCLUDE_NOTIFICATIONS or INCLUDE_GENERAL or INCLUDE_APP_OVERRIDES or INCLUDE_OTHER

        private fun deleteActiveLauncherDatabase(context: Context) {
            val database = context.getDatabasePath(LauncherAppState.getIDP(context).dbFile)
            database.delete()
            File(database.path + "-wal").delete()
            File(database.path + "-shm").delete()
            File(database.path + "-journal").delete()
        }

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
            val idp = LauncherAppState.getIDP(context)
            val createdAt = Timestamp.newBuilder()
                .setSeconds(System.currentTimeMillis() / 1000)
            val colorHints = WallpaperManagerCompat.INSTANCE.get(context).wallpaperColors?.colorHints ?: 0
            val wallpaperSupportsDarkText = (colorHints and WallpaperColorsCompat.HINT_SUPPORTS_DARK_TEXT) != 0
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

            val pfd = context.contentResolver.openFileDescriptor(fileUri, "w")!!
            withContext(Dispatchers.IO) {
                pfd.use {
                    ZipOutputStream(FileOutputStream(pfd.fileDescriptor).buffered()).use { out ->
                        out.putNextEntry(ZipEntry(INFO_FILE_NAME))
                        info.writeTo(out)

                        if (contents.hasFlag(INCLUDE_WALLPAPER)) {
                            val wallpaperManager = WallpaperManager.getInstance(context)
                            val wallpaperBitmap = wallpaperManager.drawable?.toBitmap()
                            require(wallpaperBitmap != null) {
                                "Wallpaper could not be read for backup"
                            }
                            out.putNextEntry(ZipEntry(WALLPAPER_FILE_NAME))
                            wallpaperBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        val selectedModules = modulesForContents(contents)
                        if (selectedModules.isNotEmpty()) {
                            out.putNextEntry(ZipEntry(SCREENSHOT_FILE_NAME))
                            screenshotBitmap.compress(Bitmap.CompressFormat.PNG, 85, out)
                            out.putNextEntry(ZipEntry(MODULE_PREFERENCES_FILE_NAME))
                            out.write(ModularPreferenceBackup.export(context, selectedModules))
                        }
                        if ("typography" in selectedModules) {
                            val fonts = FontCache.TTFFont.getFontsDir(context).listFiles()
                                ?.filter { it.isFile } ?: emptyList()
                            require(fonts.size <= MAX_CUSTOM_FONT_FILES) { "Too many custom fonts to back up" }
                            fonts.forEach { font ->
                                validateFontEntryName(font.name)
                                require(font.length() <= MAX_CUSTOM_FONT_BYTES) { "Custom font is too large" }
                                out.putNextEntry(ZipEntry(CUSTOM_FONT_ENTRY_PREFIX + font.name))
                                font.inputStream().use { input -> input.copyTo(out) }
                            }
                        }

                        if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
                            val database = launcherDbFile(context, forRestore = false)
                            require(database.isFile && database.length() in 1..MAX_DATABASE_BYTES) {
                                "Launcher database is unavailable or exceeds the backup size limit"
                            }
                            out.putNextEntry(ZipEntry(LAUNCHER_DB_FILE_NAME))
                            database.inputStream().use { input -> input.copyTo(out) }
                            val wal = File(database.path + "-wal")
                            if (wal.isFile && wal.length() > 0L) {
                                require(wal.length() <= MAX_DATABASE_BYTES) {
                                    "Launcher database WAL exceeds the backup size limit"
                                }
                                out.putNextEntry(ZipEntry(LAUNCHER_DB_WAL_FILE_NAME))
                                wal.inputStream().use { input -> input.copyTo(out) }
                            }
                        }
                    }
                }
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

        private fun prefsDbFile(context: Context): File {
            return context.getDatabasePath(PREFS_DB_FILE_NAME)
        }

        private fun prefsDataStoreFile(context: Context): File {
            return File(context.filesDir, "datastore/${PREFS_DATASTORE_FILE_NAME}")
        }
    }
}
