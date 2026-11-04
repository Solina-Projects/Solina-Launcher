package app.lawnchair.backup

import android.content.ContentValues
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Xml
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.lawnchair.LawnchairProto.BackupInfo
import app.lawnchair.data.AppDatabase
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.security.AppLockStore
import app.lawnchair.util.hasFlag
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherPrefs.Companion.ENABLE_TWOLINE_ALLAPPS_TOGGLE
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.DeviceGridState
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

internal object ModularBackupData {
    private const val SCHEMA_VERSION = 1
    private const val MAX_SNAPSHOT_BYTES = 16 * 1024 * 1024
    private const val BOOT_AWARE_PREFS_NAME = "boot_aware_prefs"

    private val NON_RESTORABLE_SYSTEM_KEYS = setOf(
        "version",
        "db_files",
        "restored_task_pending",
        "first_load_after_restore",
        "appwidget_old_ids",
        "appwidget_ids",
    )

    private val HOME_SCREEN_KEYS = setOf(
        "pref_workspaceColumns",
        "pref_workspaceRows",
        "pref_workspace_increase_max_grid_size",
        "pref_allowRotation",
        "pref_add_icon_to_home",
        "pref_wallpaperScrolling",
        "pref_infiniteScrolling",
        "migration_src_workspace_size",
        "migration_src_device_type",
        "migration_src_db_file",
        "migration_src_grid_type",
        "home_icon_size_factor",
        "show_icon_labels_on_home_screen",
        "home_icon_label_size_factor",
        "home_icon_label_vertical_offset",
        "home_icon_label_color",
        "workspace_text_color",
        "workspace_padding_horizontal_factor",
        "workspace_padding_vertical_factor",
        "page_indicator_height_factor",
        "default_home_page",
        "lock_home_screen",
        "enable_lawn_deck",
        "show_deck_layout",
        "launcher_popup_order",
        "lock_home_screen_on_popup",
        "edit_home_screen_on_popup",
        "show_system_settings_entry_on_popup",
        "legacy_popup_options_migrated",
        "rounded_widgets",
        "allow_widget_overlap",
        "force_widget_resize",
        "widget_unlimited_size",
        "widget_padding_factor",
        "show_status_bar",
        "status_bar_clock",
        "dark_status_bar",
        "show_top_shadow",
        "pref_homePreferencesShowLivePreview",
    )

    private val DOCK_KEYS = setOf(
        "pref_hotseatColumns",
        "pref_hotseatColumnsUnfolded",
        "pref_hotseatRows",
        "pref_dockPages",
        "pref_hotseatBG",
        "pref_hotseatBGStylePreset",
        "pref_hotseatBGGlassBorder",
        "pref_hotseatBGGlassBorderWidth",
        "pref_hotseatBGGlossySheen",
        "pref_hotseatBGHRinsetLeft",
        "pref_hotseatBGVRinsetTop",
        "pref_hotseatBGHRinsetRight",
        "pref_hotseatBGVRinsetBottom",
        "pref_hotseatBGTransparency",
        "migration_src_hotseat_count",
        "pref_show_hotseat",
        "hotseat_mode",
        "hotseat_icon_size_factor",
        "enable_label_dock",
        "hotseat_icon_label_size_factor",
        "hotseat_icon_label_vertical_offset",
        "dock_icon_label_color",
        "hotseat_row_space_factor",
        "hotseat_icon_spacing_factor",
        "hotseat_vertical_padding_dp",
        "hotseat_bottom_factor",
        "hotseat_qsb_vertical_offset",
        "hotseat_bg_color",
        "hotseat_bg_corner_radius",
    )

    private val APP_DRAWER_KEYS = setOf(
        "pref_drawerOpacity",
        "pref_drawerList",
        "enableTwolineAllAppsToggle",
        "drawer_columns",
        "drawer_columns_unfolded",
        "drawer_icon_size_factor",
        "show_icon_labels_in_drawer",
        "drawer_icon_label_size_factor",
        "drawer_icon_label_vertical_offset",
        "drawer_icon_label_color",
        "drawer_cell_height_factor",
        "drawer_left_right_margin_factor",
        "drawer_padding_top_factor",
        "app_drawer_bg_color",
        "all_apps_remember_position",
        "all_apps_show_scrollbar",
        "app_drawer_haptic_feedback",
        "two_line_all_apps",
        "show_component_names",
        "work_profile_tab_background_color",
        "work_profile_tab_container_background",
        "hidden_apps",
        "hidden_apps_in_search",
    )

    private val SUGGESTION_KEYS = setOf(
        "show_suggested_apps_at_drawer_top",
        "suggestion_rows",
        "suggestion_row_height_factor",
        "suggestion_position",
        "suggestion_source",
        "suggestion_icon_scale",
        "suggestion_label_scale",
        "suggestion_label_color",
        "suggestion_label_vertical_offset",
        "suggestion_labels_visible",
        "suggestion_background_enabled",
        "suggestion_background_color",
        "suggestion_background_alpha",
        "suggestion_corner_radius",
        "suggestion_padding",
        "suggestion_margin",
        "enable_global_prediction",
        "prediction_mode",
        "lawnchair_prediction_use_weighted_usage",
    )

    private val SEARCH_KEYS = setOf(
        "pref_hotseatQsbCornerRadius",
        "pref_searchHotseatTranparency",
        "pref_searchStrokeWidth",
        "device_search",
        "pref_searchResultShortcuts",
        "pref_searchResultPeople",
        "pref_searchResultPixelTips",
        "pref_searchResultSettings",
        "pref_searchResultCalculator",
        "pref_searchResultApps",
        "pref_searchResultFiles",
        "pref_searchResultAllFiles",
        "pref_searchResultAudio",
        "pref_searchResultVisualMedia",
        "pref_searchResultStartPageSuggestion",
        "pref_searchResultSettingsEntry",
        "pref_searchResultRecentSuggestion",
        "pref_qsbSurfaceStyle",
        "pref_qsbShowHintText",
        "pref_qsbEndIconPillBg",
        "qsb_3d_enabled",
        "qsb_3d_depth",
        "qsb_icons_3d",
        "qsb_background_color",
        "qsb_icon_color",
        "themed_hotseat_qsb",
        "match_hotseat_qsb_style",
        "dock_search_bar_provider",
        "dock_search_bar_force_website",
        "hide_app_drawer_search_bar",
        "all_apps_search_bar_background",
        "auto_show_keyboard_in_drawer",
        "enable_fuzzy_search",
        "search_algorithm",
        "web_suggestion_provider",
        "web_suggestion_provider_name",
        "web_suggestion_provider_url",
        "web_suggestion_provider_suggestions_url",
        "max_app_search_result_count",
        "max_web_suggestion_result_count",
        "max_file_result_count",
        "max_people_result_count",
        "max_web_suggestion_delay",
        "max_settings_entry_result_count",
        "max_recent_result_count",
    )

    private val FOLDER_KEYS = setOf(
        "pref_folderRows",
        "pref_hideFolderApps",
        "pref_drawerListOrder",
        "folder_columns",
        "folder_shape",
        "custom_folder_shape",
        "folder_color",
        "folder_preview_bg_opacity",
        "folder_bg_opacity",
        "show_icon_labels_on_home_screen_folder",
        "home_icon_label_folder_size_factor",
    )

    private val THEME_AND_FONTS_KEYS = setOf(
        "pref_launcherTheme",
        "pref_coloredBackgroundLightness",
        "pref_overrideWindowCornerRadius",
        "pref_windowCornerRadius",
        "pref_enableWallpaperBlur",
        "pref_wallpaperBlur",
        "pref_wallpaperBlurFactor",
        "pref_workspaceFont",
        "pref_fontHeading",
        "pref_fontHeadingMedium",
        "pref_fontBody",
        "pref_fontBodyMedium",
        "accent_color",
        "color_style",
        "stroke_color",
        "notification_dot_color",
        "notification_dot_text_color",
        "show_notification_count",
        "enable_wallpaper_depth_effect",
        "closing_app_overlay",
        "enable_font_selection",
        "additional_fonts",
    )

    private val ICON_KEYS = setOf(
        "pref_iconPackPackage",
        "pref_themedIconPackPackage",
        "themed_icons",
        "drawer_themed_icons",
        "prefs_wrapAdaptive",
        "prefs_transparentIconBackground",
        "pref_shadowBGIcons",
        "pref_forceIconMonochrome",
        "pref_appNameMap",
        "icon_shape",
        "custom_icon_shape",
        "always_reload_icons",
    )

    fun flagForKey(key: String): Int {
        if (key in HOME_SCREEN_KEYS) return LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS
        if (key in DOCK_KEYS) return LawnchairBackup.INCLUDE_DOCK
        if (key in APP_DRAWER_KEYS) return LawnchairBackup.INCLUDE_APP_DRAWER
        if (key in SUGGESTION_KEYS) return LawnchairBackup.INCLUDE_SUGGESTION_SETTINGS
        if (key in SEARCH_KEYS) return LawnchairBackup.INCLUDE_SEARCH
        if (key in FOLDER_KEYS) return LawnchairBackup.INCLUDE_FOLDERS
        if (key in THEME_AND_FONTS_KEYS) return LawnchairBackup.INCLUDE_THEME_AND_FONTS
        if (key in ICON_KEYS) return LawnchairBackup.INCLUDE_ICONS
        val lower = key.lowercase()
        return when {
            lower.startsWith("suggestion_") || lower.contains("prediction") ->
                LawnchairBackup.INCLUDE_SUGGESTION_SETTINGS

            lower.contains("hotseat") || lower.startsWith("dock_") || lower.contains("dock") ->
                LawnchairBackup.INCLUDE_DOCK

            lower.contains("drawer") || lower.startsWith("all_apps") || lower.contains("hidden_apps") ->
                LawnchairBackup.INCLUDE_APP_DRAWER

            lower.contains("qsb") || lower.contains("search") || lower.contains("web_suggestion") ->
                LawnchairBackup.INCLUDE_SEARCH

            lower.contains("folder") ->
                LawnchairBackup.INCLUDE_FOLDERS

            lower.contains("font") || lower.contains("theme") || lower.contains("accent") ||
                lower.contains("color_style") || lower.contains("stroke_color") ||
                lower.contains("blur") || lower.contains("notification_dot") ->
                LawnchairBackup.INCLUDE_THEME_AND_FONTS

            lower.contains("icon_pack") || lower.contains("iconpack") || lower.contains("icon_shape") ||
                lower.contains("appnamemap") || lower.contains("themed_icons") ->
                LawnchairBackup.INCLUDE_ICONS

            lower.contains("workspace") || lower.contains("home_icon") || lower.contains("widget") ||
                lower.contains("page_indicator") || lower.contains("lawn_deck") ||
                lower.contains("deck_layout") || lower.contains("_on_popup") ->
                LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS

            else -> LawnchairBackup.INCLUDE_GESTURES_AND_EXTRAS
        }
    }

    private fun isKeySelected(key: String, selectedContents: Int): Boolean {
        if (key in NON_RESTORABLE_SYSTEM_KEYS) return false
        return selectedContents.hasFlag(flagForKey(key))
    }

    suspend fun exportSnapshot(context: Context, selectedContents: Int): ByteArray {
        val root = JSONObject()
        root.put("schema", SCHEMA_VERSION)
        root.put("contents", selectedContents)

        val shared = JSONObject()
        PreferenceManager.getInstance(context).sp.all.forEach { (key, value) ->
            if (value != null && isKeySelected(key, selectedContents)) {
                shared.put(key, encodeValue(value))
            }
        }
        root.put("shared_preferences", shared)

        val devicePrefs = JSONObject()
        LauncherPrefs.getDevicePrefs(context).all.forEach { (key, value) ->
            if (value != null && isKeySelected(key, selectedContents)) {
                devicePrefs.put(key, encodeValue(value))
            }
        }
        root.put("device_preferences", devicePrefs)

        val bootAware = JSONObject()
        getBootAwarePrefs(context).all.forEach { (key, value) ->
            if (value != null && isKeySelected(key, selectedContents)) {
                bootAware.put(key, encodeValue(value))
            }
        }
        root.put("boot_aware_preferences", bootAware)

        val dataStoreSnapshot = PreferenceManager2.getInstance(context).preferencesDataStore.data.first()
        val dataStoreJson = JSONObject()
        dataStoreSnapshot.asMap().forEach { (key, value) ->
            if (isKeySelected(key.name, selectedContents)) {
                dataStoreJson.put(key.name, encodeValue(value))
            }
        }
        root.put("datastore_preferences", dataStoreJson)

        if (selectedContents.hasFlag(LawnchairBackup.INCLUDE_GESTURES_AND_EXTRAS)) {
            val lockedPackages = JSONArray(AppLockStore.lockedPackages(context).sorted())
            root.put("app_lock_packages", lockedPackages)
        }

        val roomDb = AppDatabase.INSTANCE.get(context).openHelper.writableDatabase
        if (selectedContents.hasFlag(LawnchairBackup.INCLUDE_FOLDERS)) {
            root.put("drawer_folders", exportDrawerFolders(roomDb))
        }
        if (selectedContents.hasFlag(LawnchairBackup.INCLUDE_ICONS)) {
            root.put("icon_overrides", exportIconOverrides(roomDb))
        }
        if (selectedContents.hasFlag(LawnchairBackup.INCLUDE_WALLPAPER) ||
            selectedContents.hasFlag(LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS)
        ) {
            root.put("wallpaper_history", exportWallpaperHistory(roomDb))
        }

        return root.toString().toByteArray(Charsets.UTF_8)
    }

    suspend fun createSnapshotFromLegacyFiles(
        stagedSharedPrefsXml: File?,
        stagedDataStoreFile: File?,
        stagedRoomDbFile: File?,
        contents: Int,
    ): ByteArray {
        val root = JSONObject()
        root.put("schema", SCHEMA_VERSION)
        root.put("contents", contents)

        val shared = if (stagedSharedPrefsXml != null && stagedSharedPrefsXml.isFile) {
            parseSharedPreferencesXml(stagedSharedPrefsXml)
        } else {
            JSONObject()
        }
        root.put("shared_preferences", shared)
        root.put("device_preferences", JSONObject())
        root.put("boot_aware_preferences", JSONObject())

        val dataStoreJson = if (stagedDataStoreFile != null && stagedDataStoreFile.isFile) {
            readDataStoreFileToJson(stagedDataStoreFile)
        } else {
            JSONObject()
        }
        root.put("datastore_preferences", dataStoreJson)

        if (stagedRoomDbFile != null && stagedRoomDbFile.isFile && stagedRoomDbFile.length() > 0L) {
            SQLiteDatabase.openDatabase(
                stagedRoomDbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                root.put("drawer_folders", exportDrawerFoldersFromSqlite(db))
                root.put("icon_overrides", exportIconOverridesFromSqlite(db))
                root.put("wallpaper_history", exportWallpaperHistoryFromSqlite(db))
            }
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun validateSnapshot(bytes: ByteArray) {
        require(bytes.size <= MAX_SNAPSHOT_BYTES) { "Modular settings snapshot exceeds size limit" }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.optInt("schema", -1) == SCHEMA_VERSION) {
            "Unsupported modular settings schema"
        }
        root.optJSONObject("shared_preferences")?.let(::validateEncodedMap)
        root.optJSONObject("device_preferences")?.let(::validateEncodedMap)
        root.optJSONObject("boot_aware_preferences")?.let(::validateEncodedMap)
        root.optJSONObject("datastore_preferences")?.let(::validateEncodedMap)
        root.optJSONArray("app_lock_packages")?.let { array ->
            for (index in 0 until array.length()) {
                require(array.opt(index) is String) { "Invalid package name in app lock backup" }
            }
        }
    }

    private fun validateEncodedMap(mapJson: JSONObject) {
        mapJson.keys().forEach { key ->
            val encoded = mapJson.optJSONObject(key)
                ?: throw IllegalArgumentException("Invalid encoded preference entry for $key")
            val value = encoded.opt("value")
            val numericValue = (value as? Number)?.toDouble()
            val isValid = when (encoded.optString("type")) {
                "boolean" -> value is Boolean

                "int" -> numericValue != null && numericValue.isFinite() &&
                    numericValue % 1.0 == 0.0 &&
                    numericValue >= Int.MIN_VALUE && numericValue <= Int.MAX_VALUE

                "long" -> numericValue != null && numericValue.isFinite() &&
                    numericValue >= Long.MIN_VALUE.toDouble() &&
                    numericValue <= Long.MAX_VALUE.toDouble()

                "float" -> numericValue != null && numericValue.isFinite() &&
                    numericValue >= -Float.MAX_VALUE && numericValue <= Float.MAX_VALUE

                "double" -> numericValue != null && numericValue.isFinite()

                "string" -> value is String

                "string_set" -> value is JSONArray && (0 until value.length()).all {
                    value.opt(it) is String
                }

                else -> false
            }
            require(isValid) { "Unsupported or out-of-range preference value for $key" }
        }
    }

    suspend fun restoreSnapshot(
        context: Context,
        bytes: ByteArray,
        selectedContents: Int,
        backupInfo: BackupInfo? = null,
    ) {
        validateSnapshot(bytes)
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val prefManager = PreferenceManager.getInstance(context)
        prefManager.suppressListeners = true
        try {
            val sharedJson = root.optJSONObject("shared_preferences") ?: JSONObject()
            val sp = prefManager.sp
            val spEditor = sp.edit()
            sp.all.keys
                .filter { isKeySelected(it, selectedContents) }
                .forEach(spEditor::remove)
            sharedJson.keys().forEach { key ->
                if (isKeySelected(key, selectedContents)) {
                    putDecodedIntoSharedPreferences(spEditor, key, sharedJson.getJSONObject(key))
                }
            }
            if (selectedContents.hasFlag(LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS) &&
                backupInfo != null && backupInfo.hasGridState()
            ) {
                val gridState = DeviceGridState(backupInfo.gridState)
                if (!sharedJson.has("pref_workspaceColumns") && gridState.columns > 0) {
                    spEditor.putInt("pref_workspaceColumns", gridState.columns)
                }
                if (!sharedJson.has("pref_workspaceRows") && gridState.rows > 0) {
                    spEditor.putInt("pref_workspaceRows", gridState.rows)
                }
            }
            if (selectedContents.hasFlag(LawnchairBackup.INCLUDE_DOCK) &&
                backupInfo != null && backupInfo.hasGridState()
            ) {
                val gridState = DeviceGridState(backupInfo.gridState)
                if (!sharedJson.has("pref_hotseatColumns") && gridState.numHotseat > 0) {
                    spEditor.putInt("pref_hotseatColumns", gridState.numHotseat)
                }
            }
            spEditor.putInt("version", 2)
            check(spEditor.commit()) { "Unable to commit SharedPreferences restore" }
            prefManager.reloadFromStorage()

            val devicePrefsJson = root.optJSONObject("device_preferences") ?: JSONObject()
            val devicePrefs = LauncherPrefs.getDevicePrefs(context)
            val deviceEditor = devicePrefs.edit()
            devicePrefs.all.keys
                .filter { isKeySelected(it, selectedContents) }
                .forEach(deviceEditor::remove)
            devicePrefsJson.keys().forEach { key ->
                if (isKeySelected(key, selectedContents)) {
                    putDecodedIntoSharedPreferences(deviceEditor, key, devicePrefsJson.getJSONObject(key))
                }
            }
            check(deviceEditor.commit()) { "Unable to commit device preferences restore" }

            val bootAwareJson = root.optJSONObject("boot_aware_preferences") ?: JSONObject()
            val bootAwarePrefs = getBootAwarePrefs(context)
            val bootAwareEditor = bootAwarePrefs.edit()
            bootAwarePrefs.all.keys
                .filter { isKeySelected(it, selectedContents) }
                .forEach(bootAwareEditor::remove)
            bootAwareJson.keys().forEach { key ->
                if (isKeySelected(key, selectedContents)) {
                    putDecodedIntoSharedPreferences(bootAwareEditor, key, bootAwareJson.getJSONObject(key))
                }
            }
            check(bootAwareEditor.commit()) { "Unable to commit boot-aware preferences restore" }

            val dataStoreJson = root.optJSONObject("datastore_preferences") ?: JSONObject()
            val prefManager2 = PreferenceManager2.getInstance(context)
            prefManager2.preferencesDataStore.edit { current ->
                current.asMap().keys
                    .filter { isKeySelected(it.name, selectedContents) }
                    .forEach { current.remove(it) }
                dataStoreJson.keys().forEach { name ->
                    if (!isKeySelected(name, selectedContents)) return@forEach
                    val encoded = dataStoreJson.getJSONObject(name)
                    when (val value = decodeValue(encoded)) {
                        is Boolean -> current[booleanPreferencesKey(name)] = value

                        is Int -> current[intPreferencesKey(name)] = value

                        is Long -> current[longPreferencesKey(name)] = value

                        is Float -> current[floatPreferencesKey(name)] = value

                        is Double -> current[doublePreferencesKey(name)] = value

                        is String -> current[stringPreferencesKey(name)] = value

                        is Set<*> -> current[stringSetPreferencesKey(name)] =
                            value.filterIsInstance<String>().toSet()
                    }
                }
            }
            prefManager2.reloadFromDataStore()

            if (selectedContents.hasFlag(LawnchairBackup.INCLUDE_APP_DRAWER) &&
                dataStoreJson.has("two_line_all_apps")
            ) {
                val twoLine = decodeValue(dataStoreJson.getJSONObject("two_line_all_apps")) as? Boolean
                if (twoLine != null) {
                    LauncherPrefs.get(context).putSync(ENABLE_TWOLINE_ALLAPPS_TOGGLE.to(twoLine))
                }
            }

            if (selectedContents.hasFlag(LawnchairBackup.INCLUDE_GESTURES_AND_EXTRAS) &&
                root.has("app_lock_packages")
            ) {
                val array = root.getJSONArray("app_lock_packages")
                val packages = buildSet {
                    for (index in 0 until array.length()) {
                        add(array.getString(index))
                    }
                }
                AppLockStore.replaceLockedPackages(context, packages)
            }

            val appDb = AppDatabase.INSTANCE.get(context)
            val writableDb = appDb.openHelper.writableDatabase
            var roomMutated = false
            if (selectedContents.hasFlag(LawnchairBackup.INCLUDE_FOLDERS) &&
                root.has("drawer_folders")
            ) {
                restoreDrawerFolders(writableDb, root.getJSONArray("drawer_folders"))
                roomMutated = true
            }
            if (selectedContents.hasFlag(LawnchairBackup.INCLUDE_ICONS) &&
                root.has("icon_overrides")
            ) {
                restoreIconOverrides(writableDb, root.getJSONArray("icon_overrides"))
                roomMutated = true
            }
            if ((
                    selectedContents.hasFlag(LawnchairBackup.INCLUDE_WALLPAPER) ||
                        selectedContents.hasFlag(LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS)
                    ) &&
                root.has("wallpaper_history")
            ) {
                restoreWallpaperHistory(writableDb, root.getJSONArray("wallpaper_history"))
                roomMutated = true
            }
            if (roomMutated) {
                appDb.invalidationTracker.refreshVersionsAsync()
                appDb.checkpoint()
            }
        } finally {
            prefManager.suppressListeners = false
        }
    }

    /**
     * Filters a copied launcher.db in-place so that unselected containers (Home Screen vs. Dock)
     * are removed before writing into a selective backup archive or before running RestoreDbTask.
     */
    fun filterLauncherDatabaseContainers(
        databaseFile: File,
        keepHomeScreen: Boolean,
        keepDock: Boolean,
    ) {
        if (keepHomeScreen && keepDock) return
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            db.beginTransaction()
            try {
                if (!keepDock) {
                    db.execSQL(
                        "DELETE FROM ${Favorites.TABLE_NAME} " +
                            "WHERE ${Favorites.CONTAINER} IN (${Favorites.CONTAINER_HOTSEAT}, ${Favorites.CONTAINER_HOTSEAT_PREDICTION}) " +
                            "OR ${Favorites.CONTAINER} IN (" +
                            "SELECT ${Favorites._ID} FROM ${Favorites.TABLE_NAME} " +
                            "WHERE ${Favorites.CONTAINER} IN (${Favorites.CONTAINER_HOTSEAT}, ${Favorites.CONTAINER_HOTSEAT_PREDICTION})" +
                            ")",
                    )
                }
                if (!keepHomeScreen) {
                    db.execSQL(
                        "DELETE FROM ${Favorites.TABLE_NAME} " +
                            "WHERE ${Favorites.CONTAINER} NOT IN (${Favorites.CONTAINER_HOTSEAT}, ${Favorites.CONTAINER_HOTSEAT_PREDICTION}) " +
                            "AND ${Favorites.CONTAINER} NOT IN (" +
                            "SELECT ${Favorites._ID} FROM ${Favorites.TABLE_NAME} " +
                            "WHERE ${Favorites.CONTAINER} IN (${Favorites.CONTAINER_HOTSEAT}, ${Favorites.CONTAINER_HOTSEAT_PREDICTION})" +
                            ")",
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * Copies preserved Home Screen or Dock rows (including folders and their child items) from
     * [preservedDbFile] into [targetDbFile], assigning fresh `_id` values so there is no primary
     * key collision with restored rows.
     */
    fun mergePreservedLauncherContainers(
        targetDbFile: File,
        preservedDbFile: File,
        copyHomeScreenFromPreserved: Boolean,
        copyDockFromPreserved: Boolean,
    ) {
        if (!copyHomeScreenFromPreserved && !copyDockFromPreserved) return
        if (!preservedDbFile.isFile || preservedDbFile.length() == 0L) return

        SQLiteDatabase.openDatabase(
            preservedDbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { sourceDb ->
            if (!hasTable(sourceDb, Favorites.TABLE_NAME)) return
            SQLiteDatabase.openDatabase(
                targetDbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { targetDb ->
                if (!hasTable(targetDb, Favorites.TABLE_NAME)) return
                val commonColumns = getTableColumns(sourceDb, Favorites.TABLE_NAME)
                    .intersect(getTableColumns(targetDb, Favorites.TABLE_NAME))
                if (Favorites._ID !in commonColumns || Favorites.CONTAINER !in commonColumns) return

                targetDb.beginTransaction()
                try {
                    var nextId = getMaxId(targetDb) + 1
                    val topLevelCondition = when {
                        copyHomeScreenFromPreserved && !copyDockFromPreserved ->
                            "${Favorites.CONTAINER} = ${Favorites.CONTAINER_DESKTOP}"

                        !copyHomeScreenFromPreserved && copyDockFromPreserved ->
                            "${Favorites.CONTAINER} IN (${Favorites.CONTAINER_HOTSEAT}, ${Favorites.CONTAINER_HOTSEAT_PREDICTION})"

                        else ->
                            "${Favorites.CONTAINER} IN (${Favorites.CONTAINER_DESKTOP}, ${Favorites.CONTAINER_HOTSEAT}, ${Favorites.CONTAINER_HOTSEAT_PREDICTION})"
                    }
                    val folderIdMap = mutableMapOf<Int, Int>()
                    sourceDb.rawQuery(
                        "SELECT * FROM ${Favorites.TABLE_NAME} WHERE $topLevelCondition",
                        null,
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val oldId = cursor.getInt(cursor.getColumnIndexOrThrow(Favorites._ID))
                            val newId = nextId++
                            folderIdMap[oldId] = newId
                            val values = cursorRowToContentValues(cursor, commonColumns)
                            values.put(Favorites._ID, newId)
                            targetDb.insertOrThrow(Favorites.TABLE_NAME, null, values)
                        }
                    }

                    if (folderIdMap.isNotEmpty()) {
                        val oldIdsList = folderIdMap.keys.joinToString(",")
                        sourceDb.rawQuery(
                            "SELECT * FROM ${Favorites.TABLE_NAME} WHERE ${Favorites.CONTAINER} IN ($oldIdsList)",
                            null,
                        ).use { cursor ->
                            while (cursor.moveToNext()) {
                                val oldContainer = cursor.getInt(
                                    cursor.getColumnIndexOrThrow(Favorites.CONTAINER),
                                )
                                val mappedContainer = folderIdMap[oldContainer] ?: continue
                                val newId = nextId++
                                val values = cursorRowToContentValues(cursor, commonColumns)
                                values.put(Favorites._ID, newId)
                                values.put(Favorites.CONTAINER, mappedContainer)
                                targetDb.insertOrThrow(Favorites.TABLE_NAME, null, values)
                            }
                        }
                    }
                    targetDb.setTransactionSuccessful()
                } finally {
                    targetDb.endTransaction()
                }
            }
        }
    }

    private fun getMaxId(db: SQLiteDatabase): Int {
        db.rawQuery("SELECT COALESCE(MAX(${Favorites._ID}), 0) FROM ${Favorites.TABLE_NAME}", null)
            .use { cursor ->
                return if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
    }

    private fun getTableColumns(db: SQLiteDatabase, tableName: String): Set<String> {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIdx >= 0) columns.add(cursor.getString(nameIdx))
            }
        }
        return columns
    }

    private fun cursorRowToContentValues(
        cursor: Cursor,
        allowedColumns: Set<String>,
    ): ContentValues {
        val values = ContentValues()
        for (i in 0 until cursor.columnCount) {
            val colName = cursor.getColumnName(i)
            if (colName !in allowedColumns) continue
            when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> values.putNull(colName)
                Cursor.FIELD_TYPE_INTEGER -> values.put(colName, cursor.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> values.put(colName, cursor.getDouble(i))
                Cursor.FIELD_TYPE_STRING -> values.put(colName, cursor.getString(i))
                Cursor.FIELD_TYPE_BLOB -> values.put(colName, cursor.getBlob(i))
            }
        }
        return values
    }

    private fun hasTable(db: SQLiteDatabase, tableName: String): Boolean {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(tableName),
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun exportDrawerFolders(db: androidx.sqlite.db.SupportSQLiteDatabase): JSONArray {
        val result = JSONArray()
        db.query("SELECT id, title, hide, rank, timestamp FROM Folders ORDER BY rank ASC").use { fCursor ->
            while (fCursor.moveToNext()) {
                val folderId = fCursor.getInt(0)
                val folderObj = JSONObject()
                    .put("id", folderId)
                    .put("title", fCursor.getString(1))
                    .put("hide", fCursor.getInt(2) != 0)
                    .put("rank", fCursor.getInt(3))
                    .put("timestamp", fCursor.getLong(4))
                val itemsArray = JSONArray()
                db.query(
                    "SELECT rank, item_info, timestamp FROM FolderItems WHERE folderId = ? ORDER BY rank ASC",
                    arrayOf(folderId),
                ).use { iCursor ->
                    while (iCursor.moveToNext()) {
                        val itemObj = JSONObject()
                            .put("rank", iCursor.getInt(0))
                            .put("componentKey", if (iCursor.isNull(1)) JSONObject.NULL else iCursor.getString(1))
                            .put("timestamp", iCursor.getLong(2))
                        itemsArray.put(itemObj)
                    }
                }
                folderObj.put("items", itemsArray)
                result.put(folderObj)
            }
        }
        return result
    }

    private fun exportDrawerFoldersFromSqlite(db: SQLiteDatabase): JSONArray {
        val result = JSONArray()
        if (!hasTable(db, "Folders")) return result
        db.rawQuery("SELECT id, title, hide, rank, timestamp FROM Folders ORDER BY rank ASC", null)
            .use { fCursor ->
                while (fCursor.moveToNext()) {
                    val folderId = fCursor.getInt(0)
                    val folderObj = JSONObject()
                        .put("id", folderId)
                        .put("title", fCursor.getString(1))
                        .put("hide", fCursor.getInt(2) != 0)
                        .put("rank", fCursor.getInt(3))
                        .put("timestamp", fCursor.getLong(4))
                    val itemsArray = JSONArray()
                    if (hasTable(db, "FolderItems")) {
                        db.rawQuery(
                            "SELECT rank, item_info, timestamp FROM FolderItems WHERE folderId = ? ORDER BY rank ASC",
                            arrayOf(folderId.toString()),
                        ).use { iCursor ->
                            while (iCursor.moveToNext()) {
                                val itemObj = JSONObject()
                                    .put("rank", iCursor.getInt(0))
                                    .put(
                                        "componentKey",
                                        if (iCursor.isNull(1)) JSONObject.NULL else iCursor.getString(1),
                                    )
                                    .put("timestamp", iCursor.getLong(2))
                                itemsArray.put(itemObj)
                            }
                        }
                    }
                    folderObj.put("items", itemsArray)
                    result.put(folderObj)
                }
            }
        return result
    }

    private fun restoreDrawerFolders(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        foldersArray: JSONArray,
    ) {
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM FolderItems")
            db.execSQL("DELETE FROM Folders")
            for (i in 0 until foldersArray.length()) {
                val folderObj = foldersArray.getJSONObject(i)
                val folderValues = ContentValues().apply {
                    put("title", folderObj.getString("title"))
                    put("hide", if (folderObj.optBoolean("hide", false)) 1 else 0)
                    put("rank", folderObj.optInt("rank", i))
                    put("timestamp", folderObj.optLong("timestamp", System.currentTimeMillis()))
                }
                val newFolderId = db.insert("Folders", SQLiteDatabase.CONFLICT_REPLACE, folderValues)
                val itemsArray = folderObj.optJSONArray("items") ?: continue
                for (j in 0 until itemsArray.length()) {
                    val itemObj = itemsArray.getJSONObject(j)
                    val itemValues = ContentValues().apply {
                        put("folderId", newFolderId.toInt())
                        put("rank", itemObj.optInt("rank", j))
                        if (itemObj.isNull("componentKey")) {
                            putNull("item_info")
                        } else {
                            put("item_info", itemObj.getString("componentKey"))
                        }
                        put("timestamp", itemObj.optLong("timestamp", System.currentTimeMillis()))
                    }
                    db.insert("FolderItems", SQLiteDatabase.CONFLICT_REPLACE, itemValues)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun exportIconOverrides(db: androidx.sqlite.db.SupportSQLiteDatabase): JSONArray {
        val result = JSONArray()
        db.query("SELECT target, packPackageName, drawableName, label, type FROM IconOverride")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    result.put(
                        JSONObject()
                            .put("target", cursor.getString(0))
                            .put("packPackageName", cursor.getString(1))
                            .put("drawableName", cursor.getString(2))
                            .put("label", cursor.getString(3))
                            .put("type", cursor.getString(4)),
                    )
                }
            }
        return result
    }

    private fun exportIconOverridesFromSqlite(db: SQLiteDatabase): JSONArray {
        val result = JSONArray()
        if (!hasTable(db, "IconOverride")) return result
        db.rawQuery(
            "SELECT target, packPackageName, drawableName, label, type FROM IconOverride",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.put(
                    JSONObject()
                        .put("target", cursor.getString(0))
                        .put("packPackageName", cursor.getString(1))
                        .put("drawableName", cursor.getString(2))
                        .put("label", cursor.getString(3))
                        .put("type", cursor.getString(4)),
                )
            }
        }
        return result
    }

    private fun restoreIconOverrides(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        overridesArray: JSONArray,
    ) {
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM IconOverride")
            for (i in 0 until overridesArray.length()) {
                val obj = overridesArray.getJSONObject(i)
                val values = ContentValues().apply {
                    put("target", obj.getString("target"))
                    put("packPackageName", obj.getString("packPackageName"))
                    put("drawableName", obj.getString("drawableName"))
                    put("label", obj.getString("label"))
                    put("type", obj.getString("type"))
                }
                db.insert("IconOverride", SQLiteDatabase.CONFLICT_REPLACE, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun exportWallpaperHistory(db: androidx.sqlite.db.SupportSQLiteDatabase): JSONArray {
        val result = JSONArray()
        db.query("SELECT id, imagePath, rank, timestamp, checksum FROM Wallpapers ORDER BY rank ASC")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    result.put(
                        JSONObject()
                            .put("id", cursor.getLong(0))
                            .put("imagePath", cursor.getString(1))
                            .put("rank", cursor.getInt(2))
                            .put("timestamp", cursor.getLong(3))
                            .put("checksum", if (cursor.isNull(4)) JSONObject.NULL else cursor.getString(4)),
                    )
                }
            }
        return result
    }

    private fun exportWallpaperHistoryFromSqlite(db: SQLiteDatabase): JSONArray {
        val result = JSONArray()
        if (!hasTable(db, "Wallpapers")) return result
        db.rawQuery(
            "SELECT id, imagePath, rank, timestamp, checksum FROM Wallpapers ORDER BY rank ASC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.put(
                    JSONObject()
                        .put("id", cursor.getLong(0))
                        .put("imagePath", cursor.getString(1))
                        .put("rank", cursor.getInt(2))
                        .put("timestamp", cursor.getLong(3))
                        .put(
                            "checksum",
                            if (cursor.isNull(4)) JSONObject.NULL else cursor.getString(4),
                        ),
                )
            }
        }
        return result
    }

    private fun restoreWallpaperHistory(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        wallpapersArray: JSONArray,
    ) {
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM Wallpapers")
            for (i in 0 until wallpapersArray.length()) {
                val obj = wallpapersArray.getJSONObject(i)
                val values = ContentValues().apply {
                    put("imagePath", obj.getString("imagePath"))
                    put("rank", obj.optInt("rank", i))
                    put("timestamp", obj.optLong("timestamp", System.currentTimeMillis()))
                    if (obj.isNull("checksum")) {
                        putNull("checksum")
                    } else {
                        put("checksum", obj.getString("checksum"))
                    }
                }
                db.insert("Wallpapers", SQLiteDatabase.CONFLICT_REPLACE, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun parseSharedPreferencesXml(xmlFile: File): JSONObject {
        val result = JSONObject()
        FileInputStream(xmlFile).use { stream ->
            val parser = Xml.newPullParser()
            parser.setInput(stream, "UTF-8")
            var eventType = parser.eventType
            var currentSetName: String? = null
            var currentSetValues: MutableList<String>? = null
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name
                        val name = parser.getAttributeValue(null, "name")
                        val attrVal = parser.getAttributeValue(null, "value")
                        if (currentSetValues != null && tag == "string") {
                            currentSetValues.add(parser.nextText())
                        } else if (name != null) {
                            when (tag) {
                                "boolean" -> result.put(name, encodeValue(attrVal.toBoolean()))

                                "int" -> attrVal?.toIntOrNull()?.let { result.put(name, encodeValue(it)) }

                                "long" -> attrVal?.toLongOrNull()?.let { result.put(name, encodeValue(it)) }

                                "float" -> attrVal?.toFloatOrNull()?.let { result.put(name, encodeValue(it)) }

                                "string" -> result.put(name, encodeValue(parser.nextText()))

                                "set" -> {
                                    currentSetName = name
                                    currentSetValues = mutableListOf()
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name == "set" && currentSetName != null && currentSetValues != null) {
                            result.put(currentSetName, encodeValue(currentSetValues.toSet()))
                            currentSetName = null
                            currentSetValues = null
                        }
                    }
                }
                eventType = parser.next()
            }
        }
        return result
    }

    private suspend fun readDataStoreFileToJson(dataStoreFile: File): JSONObject {
        val result = JSONObject()
        val tempFile = File(
            dataStoreFile.parentFile,
            "legacy_read_${System.nanoTime()}.preferences_pb",
        )
        dataStoreFile.copyTo(tempFile, overwrite = true)
        val tempScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val tempStore = PreferenceDataStoreFactory.create(
                scope = tempScope,
                produceFile = { tempFile },
            )
            val prefs = tempStore.data.first()
            prefs.asMap().forEach { (key, value) ->
                result.put(key.name, encodeValue(value))
            }
        } finally {
            tempScope.cancel()
            tempFile.delete()
        }
        return result
    }

    private fun getBootAwarePrefs(context: Context) = (context.createDeviceProtectedStorageContext() ?: context)
        .getSharedPreferences(BOOT_AWARE_PREFS_NAME, MODE_PRIVATE)

    private fun putDecodedIntoSharedPreferences(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        encoded: JSONObject,
    ) {
        when (val value = decodeValue(encoded)) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }

    private fun encodeValue(value: Any): JSONObject = when (value) {
        is Boolean -> JSONObject().put("type", "boolean").put("value", value)

        is Int -> JSONObject().put("type", "int").put("value", value)

        is Long -> JSONObject().put("type", "long").put("value", value)

        is Float -> JSONObject().put("type", "float").put("value", value.toDouble())

        is Double -> JSONObject().put("type", "double").put("value", value)

        is String -> JSONObject().put("type", "string").put("value", value)

        is Set<*> -> JSONObject()
            .put("type", "string_set")
            .put("value", JSONArray(value.filterIsInstance<String>()))

        else -> JSONObject().put("type", "string").put("value", value.toString())
    }

    private fun decodeValue(encoded: JSONObject): Any? {
        val value = encoded.opt("value")
        val number = value as? Number
        return when (encoded.optString("type")) {
            "boolean" -> value as? Boolean

            "int" -> number?.toInt()

            "long" -> number?.toLong()

            "float" -> number?.toFloat()

            "double" -> number?.toDouble()

            "string" -> value as? String

            "string_set" -> {
                val array = value as? JSONArray ?: return null
                buildSet {
                    for (index in 0 until array.length()) {
                        val item = array.optString(index, null)
                        if (item != null) add(item)
                    }
                }
            }

            else -> null
        }
    }
}
