/*
 * Copyright 2022, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.preferences

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.lawnchair.LawnchairApp
import app.lawnchair.SolinaLauncher
import app.lawnchair.font.FontCache
import app.lawnchair.util.getApkVersionComparison
import app.lawnchair.util.isGestureNavContractCompatible
import app.lawnchair.util.isOnePlusStock
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.InvariantDeviceProfile.INDEX_DEFAULT
import com.android.launcher3.LauncherAppState
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.icons.LauncherIcons
import com.android.launcher3.model.DeviceGridState
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.DaggerSingletonObject
import com.android.launcher3.util.DisplayController
import com.android.launcher3.util.Executors
import com.android.launcher3.util.SafeCloseable
import com.android.quickstep.RecentsModel
import com.google.android.msdl.data.model.FeedbackLevel
import com.google.android.msdl.domain.MSDLPlayer
import javax.inject.Inject

@LauncherAppSingleton
class PreferenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : BasePreferenceManager(context),
    SafeCloseable {
    private val idp get() = InvariantDeviceProfile.INSTANCE.get(context)
    private val dc get() = DisplayController.INSTANCE.get(context)
    private val mRecentsModel get() = RecentsModel.INSTANCE.get(context)
    private val las get() = LauncherAppState.INSTANCE.get(context)
    private val reloadIcons: () -> Unit = {
        mRecentsModel.onThemeChanged()
        Executors.MODEL_EXECUTOR.execute {
            // All of this is like refreshAndReloadLauncher in ModelInitializer
            LauncherIcons.clearPool(context)
            las.iconCache.clearMemoryCache()
            las.iconCache.updateIconParams(idp.fillResIconDpi, idp.iconBitmapSize)
            las.model.forceReload()
        }
    }
    private val reloadGrid: () -> Unit = { idp.onPreferencesChanged(context) }

    private val deviceType = dc.info.deviceType

    private val recreate = {
        SolinaLauncher.instance?.recreateIfNotScheduled()
        Unit
    }

    val iconPackPackage = StringPref("pref_iconPackPackage", "", reloadIcons)
    val themedIconPackPackage = StringPref("pref_themedIconPackPackage", "", reloadIcons)
    val allowRotation = BoolPref("pref_allowRotation", false)
    val wrapAdaptiveIcons = BoolPref("prefs_wrapAdaptive", true)
    val transparentIconBackground = BoolPref("prefs_transparentIconBackground", false)
    val shadowBGIcons = BoolPref("pref_shadowBGIcons", true)
    val addIconToHome = BoolPref("pref_add_icon_to_home", true)

    private val isPhone: Boolean get() = deviceType == InvariantDeviceProfile.TYPE_PHONE
    private val isTablet: Boolean get() = deviceType == InvariantDeviceProfile.TYPE_TABLET
    private val isFoldable: Boolean get() = deviceType == InvariantDeviceProfile.TYPE_MULTI_DISPLAY
    private val isDesktop: Boolean get() = deviceType == InvariantDeviceProfile.TYPE_DESKTOP

    val calculatedGridSpec = when {
        // This grid configuration is perfect for Phone, tested against Pixel 7,
        // alternative dense configuration can be 5x5x7
        isPhone -> LayoutConfig(4, 4, 6)

        // This grid configuration is perfect for Tablet, tested against Pixel Tablet
        isTablet -> LayoutConfig(6, 6, 5)

        // This grid configuration is perfect for Foldable, tested against Pixel 10 Pro Fold
        // Note: Hotseat column is 4 when folded, unfolded uses hotseatColumns + 2 or higher number
        // defined in numExtendedHotseatIcons from device profile
        isFoldable -> LayoutConfig(4, 4, 6, 6)

        // This grid configuration is not tested against actual desktop devices,
        // but tablet configuration works perfectly when displayed via emulator
        isDesktop -> LayoutConfig(6, 6, 5)

        // This grid configuration is the fallback for all devices type, this shouldn't be possible
        else -> LayoutConfig(4, 4, 7)
    }

    val hotseatColumns = IntPref("pref_hotseatColumns", 5, reloadGrid)
    val hotseatColumnsUnfolded = IntPref("pref_hotseatColumnsUnfolded", 4, reloadGrid)
    val hotseatRows = IntPref("pref_hotseatRows", 2, reloadGrid)
    val dockPages = IntPref("pref_dockPages", 1, reloadGrid)
    val workspaceColumns = IntPref("pref_workspaceColumns", 5)
    val workspaceRows = IntPref("pref_workspaceRows", 5)
    val workspaceIncreaseMaxGridSize = BoolPref("pref_workspace_increase_max_grid_size", false)
    val folderRows = IdpIntPref("pref_folderRows", { numFolderRows[INDEX_DEFAULT] }, reloadGrid)

    val drawerOpacity = FloatPref("pref_drawerOpacity", .8f, recreate)
    val coloredBackgroundLightness = FloatPref("pref_coloredBackgroundLightness", 1F)
    val feedProvider = StringPref("pref_feedProvider", "")
    val ignoreFeedWhitelist = BoolPref("pref_ignoreFeedWhitelist", false)
    val launcherTheme = StringPref("pref_launcherTheme", "light")
    val overrideWindowCornerRadius = BoolPref("pref_overrideWindowCornerRadius", false, recreate)
    val windowCornerRadius = IntPref("pref_windowCornerRadius", 80, recreate)
    val autoLaunchRoot = BoolPref("pref_autoLaunchRoot", false)
    val wallpaperScrolling = BoolPref("pref_wallpaperScrolling", true)
    val infiniteScrolling = BoolPref("pref_infiniteScrolling", false)
    val enableDebugMenu = BoolPref("pref_enableDebugMenu", false)
    val vibrationFeedbackLevel: IntPref = IntPref(
        "pref_vibrationFeedbackLevel",
        FeedbackLevel.DEFAULT.ordinal,
    ) {
        normalizeVibrationFeedbackLevel()
    }
    val customAppName = object : MutableMapPref<ComponentKey, String>("pref_appNameMap", reloadGrid) {
        override fun flattenKey(key: ComponentKey) = key.toString()
        override fun unflattenKey(key: String) = ComponentKey.fromString(key)!!
        override fun flattenValue(value: String) = value
        override fun unflattenValue(value: String) = value
    }

    val recentActionOrder = StringPref("pref_recentActionOrder", "0,1,2,3,4", recreate)

    private val fontCache = FontCache.INSTANCE.get(context)
    val fontWorkspace = FontPref("pref_workspaceFont", fontCache.uiText, recreate)
    val fontHeading = FontPref("pref_fontHeading", fontCache.uiRegular, recreate)
    val fontHeadingMedium = FontPref("pref_fontHeadingMedium", fontCache.uiMedium, recreate)
    val fontBody = FontPref("pref_fontBody", fontCache.uiText, recreate)
    val fontBodyMedium = FontPref("pref_fontBodyMedium", fontCache.uiTextMedium, recreate)

    // TODO REMOVE
    val deviceSearch = BoolPref("device_search", false, recreate)
    val searchResultShortcuts = BoolPref("pref_searchResultShortcuts", false)
    val searchResultPeople = BoolPref("pref_searchResultPeople", true, recreate)
    val searchResultPixelTips = BoolPref("pref_searchResultPixelTips", false)
    val searchResultSettings = BoolPref("pref_searchResultSettings", false)
    val searchResultCalculator = BoolPref("pref_searchResultCalculator", false)

    val searchResultApps = BoolPref("pref_searchResultApps", true, recreate)
    val searchResultFilesToggle = BoolPref("pref_searchResultFiles", false, recreate)
    val searchResultAllFiles = BoolPref("pref_searchResultAllFiles", false, recreate)
    val searchResultAudio = BoolPref("pref_searchResultAudio", false, recreate)
    val searchResultVisualMedia = BoolPref("pref_searchResultVisualMedia", false, recreate)
    val searchResultStartPageSuggestion = BoolPref("pref_searchResultStartPageSuggestion", true, recreate)
    val searchResultSettingsEntry = BoolPref("pref_searchResultSettingsEntry", true, recreate)
    val searchResulRecentSuggestion = BoolPref("pref_searchResultRecentSuggestion", false, recreate)

    val themedIcons = BoolPref("themed_icons", false, reloadIcons)
    val drawerThemedIcons = BoolPref("drawer_themed_icons", false, reloadIcons)

    val hotseatQsbCornerRadius = FloatPref("pref_hotseatQsbCornerRadius", 1F, recreate)
    val hotseatQsbAlpha = IntPref("pref_searchHotseatTranparency", 100, recreate)
    val hotseatQsbStrokeWidth = FloatPref("pref_searchStrokeWidth", 0F, recreate)
    val hotseatBG = BoolPref("pref_hotseatBG", true, recreate)
    val hotseatBGStylePreset = StringPref("pref_hotseatBGStylePreset", "liquid_glass", recreate)
    val hotseatBGGlassBorder = BoolPref("pref_hotseatBGGlassBorder", true, recreate)
    val hotseatBGGlassBorderWidth = IntPref("pref_hotseatBGGlassBorderWidth", 2, recreate)
    val hotseatBGGlossySheen = BoolPref("pref_hotseatBGGlossySheen", true, recreate)
    val hotseatBGHorizontalInsetLeft = IntPref("pref_hotseatBGHRinsetLeft", 5, recreate)
    val hotseatBGVerticalInsetTop = IntPref("pref_hotseatBGVRinsetTop", 0, recreate)
    val hotseatBGHorizontalInsetRight = IntPref("pref_hotseatBGHRinsetRight", 5, recreate)
    val hotseatBGVerticalInsetBottom = IntPref("pref_hotseatBGVRinsetBottom", 10, recreate)

    val hotseatBGAlpha = IntPref("pref_hotseatBGTransparency", 82, recreate)

    val enableWallpaperBlur = BoolPref("pref_enableWallpaperBlur", false, recreate)
    val wallpaperBlur = IntPref("pref_wallpaperBlur", 25, recreate)
    val wallpaperBlurFactorThreshold = FloatPref("pref_wallpaperBlurFactor", 3.0F, recreate)

    val drawerList = BoolPref("pref_drawerList", true, recreate)
    val folderApps = BoolPref("pref_hideFolderApps", true, reloadGrid)

    val recentsActionScreenshot = BoolPref("pref_recentsActionScreenshot", !isOnePlusStock)
    val recentsActionShare = BoolPref("pref_recentsActionShare", isOnePlusStock)
    val recentsActionLens = BoolPref("pref_recentsActionLens", true)
    val recentsActionClearAll = BoolPref("pref_clearAllAsAction", false)
    val recentsActionLocked = BoolPref("pref_lockedAsAction", false)
    val recentsTranslucentBackground = BoolPref("pref_recentsTranslucentBackground", false, recreate)
    val recentsTranslucentBackgroundAlpha = FloatPref("pref_recentTranslucentBackgroundAlpha", .8f, recreate)

    val hideVersionInfo = BoolPref("pref_hideVersionInfo", false)
    val pseudonymVersion = StringPref("pref_pseudonymVersion", "Bubble Tea")
    val enableGnc = BoolPref("pref_enableGnc", isGestureNavContractCompatible, recreate)
    val hasOpenedSettings = BoolPref("pref_hasOpenedSettings", false)

    val lawnchairMajorVersion = IntPref(
        "pref_lawnchairMajorVersion",
        context.getApkVersionComparison().first[0],
    )

    val forceIconMonochrome = BoolPref("pref_forceIconMonochrome", false)
    val settingsDashboardLayout = StringPref("pref_settingsDashboardLayout", "grouped")
    val settingsShowQuickStatusHeader = BoolPref("pref_settingsShowQuickStatusHeader", true)
    val settingsSearchBarEnabled = BoolPref("pref_settingsSearchBarEnabled", true)
    val settingsColorfulIcons = BoolPref("pref_settingsColorfulIcons", true)
    val settingsStepperSliders = BoolPref("pref_settingsStepperSliders", true)
    val homePreferencesShowLivePreview = BoolPref("pref_homePreferencesShowLivePreview", true)
    val qsbSurfaceStyle = StringPref("pref_qsbSurfaceStyle", "neon_border", recreate)
    val qsbShowHintText = BoolPref("pref_qsbShowHintText", false, recreate)
    val qsbEndIconPillBg = BoolPref("pref_qsbEndIconPillBg", false, recreate)
    val smartspaceCardStyle = StringPref("pref_smartspaceCardStyle", "transparent", recreate)
    val smartspaceCardAlpha = IntPref("pref_smartspaceCardAlpha", 80, recreate)
    val smartspaceCardCornerRadius = IntPref("pref_smartspaceCardCornerRadius", 22, recreate)
    val smartspaceDatePillBadge = BoolPref("pref_smartspaceDatePillBadge", false, recreate)
    val smartspaceDateTextSizeScale = IntPref("pref_smartspaceDateTextSizeScale", 100, recreate)
    val drawerShowCategoryBar = BoolPref("pref_drawerShowCategoryBar", false, recreate)
    val drawerCategoryBarStyle = StringPref("pref_drawerCategoryBarStyle", "floating_glass", recreate)
    val drawerCaddyShowCounts = BoolPref("pref_drawerCaddyShowCounts", true, recreate)
    val drawerCaddyDisplayMode = StringPref("pref_drawerCaddyDisplayMode", "folders", recreate)
    val drawerCaddyCardStyle = StringPref("pref_drawerCaddyCardStyle", "glass_card", recreate)
    val drawerCaddyCardRadius = IntPref("pref_drawerCaddyCardRadius", 20, recreate)
    val drawerApplyCardToApps = BoolPref("pref_drawerApplyCardToApps", true, recreate)
    val drawerOpenAnimationStyle = StringPref("pref_drawerOpenAnimationStyle", "dock_morph", recreate)
    val drawerAnimationSpeedFactor = FloatPref("pref_drawerAnimationSpeedFactor", 1.15f, recreate)
    val drawerOpenSpringBounce = BoolPref("pref_drawerOpenSpringBounce", true, recreate)
    val drawerDockRiseFactor = FloatPref("pref_drawerDockRiseFactor", 0.56f, recreate)
    val drawerCascadeStaggerFactor = FloatPref("pref_drawerCascadeStaggerFactor", 1.0f, recreate)
    val drawerIconScaleBloom = BoolPref("pref_drawerIconScaleBloom", true, recreate)
    val forceEnableRecents = BoolPref("pref_forceEnableRecents", false) {
        LawnchairApp.instance.refreshRecentsState()
        recreate()
    }
    val recentsActionBarStyle = StringPref("pref_recentsActionBarStyle", "floating_glass", recreate)
    val recentsActionBarRadius = IntPref("pref_recentsActionBarRadius", 24, recreate)
    val searchCardStyle = StringPref("pref_searchCardStyle", "glass_card", recreate)
    val searchCardCornerRadius = IntPref("pref_searchCardCornerRadius", 22, recreate)
    val searchCardAlpha = IntPref("pref_searchCardAlpha", 88, recreate)
    val searchResultAnimateIn = BoolPref("pref_searchResultAnimateIn", true, recreate)
    val workspacePageTransition = StringPref("pref_workspacePageTransition", "slide", recreate)
    val workspaceTransitionIntensity = FloatPref("pref_workspaceTransitionIntensity", 1.0f, recreate)
    val folderSurfaceStyle = StringPref("pref_folderSurfaceStyle", "liquid_glass", recreate)
    val folderCornerRadiusDp = IntPref("pref_folderCornerRadiusDp", 28, recreate)
    val popupSurfaceStyle = StringPref("pref_popupSurfaceStyle", "glass_card", recreate)
    val popupCornerRadiusDp = IntPref("pref_popupCornerRadiusDp", 24, recreate)
    val popupBgOpacity = IntPref("pref_popupBgOpacity", 100, recreate)

    // When true every long-press menu shares the three popup* prefs above (plus shared colors).
    // When false each menu kind below has its own style, radius and opacity.
    val popupMenuLinked = BoolPref("pref_popupMenuLinked", true, recreate)
    val popupSharedProfile = PopupProfilePrefs(popupSurfaceStyle, popupCornerRadiusDp, popupBgOpacity)
    val popupProfiles: Map<String, PopupProfilePrefs> = listOf("home_menu", "app_drawer", "app_home", "app_dock")
        .associateWith { id ->
            PopupProfilePrefs(
                StringPref("pref_popupSurfaceStyle_$id", "glass_card", recreate),
                IntPref("pref_popupCornerRadiusDp_$id", 24, recreate),
                IntPref("pref_popupBgOpacity_$id", 92, recreate),
            )
        }
    val appLockScreenStyle = StringPref("pref_appLockScreenStyle", "glass_modern")
    val appLockAutoBiometric = BoolPref("pref_appLockAutoBiometric", false)
    val liquidGlassEnabled = BoolPref("pref_liquidGlassEnabled", true, recreate)
    val liquidGlassPreset = StringPref("pref_liquidGlassPreset", "lumno", recreate)
    val liquidGlassTintSwatch = StringPref("pref_liquidGlassTintSwatch", "auto_theme", recreate)
    val liquidGlassDensity = FloatPref("pref_liquidGlassDensity", 0.25f, recreate)
    val liquidGlassBlurPx = IntPref("pref_liquidGlassBlurPx", 14, recreate)
    val liquidGlassRimLight = FloatPref("pref_liquidGlassRimLight", 1.00f, recreate)
    val liquidGlassBevel = FloatPref("pref_liquidGlassBevel", 1.00f, recreate)
    val liquidGlassRefraction = FloatPref("pref_liquidGlassRefraction", 0.15f, recreate)
    val liquidGlassDispersion = FloatPref("pref_liquidGlassDispersion", 0.15f, recreate)
    val liquidGlassRimWidthDp = IntPref("pref_liquidGlassRimWidthDp", 28, recreate)
    val liquidGlassShadow = FloatPref("pref_liquidGlassShadow", 1.00f, recreate)
    val liquidGlassApplyDock = BoolPref("pref_liquidGlassApplyDock", true, recreate)
    val liquidGlassApplyQsb = BoolPref("pref_liquidGlassApplyQsb", true, recreate)
    val liquidGlassApplySmartspace = BoolPref("pref_liquidGlassApplySmartspace", false, recreate)
    val liquidGlassApplyDrawer = BoolPref("pref_liquidGlassApplyDrawer", true, recreate)
    val liquidGlassApplySuggestions = BoolPref("pref_liquidGlassApplySuggestions", true, recreate)
    val liquidGlassApplySearchCards = BoolPref("pref_liquidGlassApplySearchCards", true, recreate)
    val liquidGlassApplyFoldersPopups = BoolPref("pref_liquidGlassApplyFoldersPopups", true, recreate)
    val liquidGlassApplyRecents = BoolPref("pref_liquidGlassApplyRecents", true, recreate)

    override fun close() {
        TODO("Not yet implemented")
    }

    private fun normalizeVibrationFeedbackLevel() {
        val storedLevel = vibrationFeedbackLevel.get()
        val normalizedLevel = storedLevel.toFeedbackLevel().ordinal
        if (storedLevel != normalizedLevel) {
            vibrationFeedbackLevel.set(normalizedLevel)
        }
        MSDLPlayer.SYSTEM_FEEDBACK_LEVEL = FeedbackLevel.entries[normalizedLevel]
    }

    init {
        sp.registerOnSharedPreferenceChangeListener(this)
        migratePrefs(CURRENT_VERSION) { oldVersion ->
            if (oldVersion < 2) {
                val gridState = DeviceGridState(context).toProtoMessage()
                if (gridState.hotseatCount != -1) {
                    val colsAndRows = gridState.gridSize.split(",")
                    workspaceColumns.set(colsAndRows[0].toInt())
                    workspaceRows.set(colsAndRows[1].toInt())
                    hotseatColumns.set(gridState.hotseatCount)
                }
            }
        }
        normalizeVibrationFeedbackLevel()
    }

    companion object {
        private const val CURRENT_VERSION = 2

        @JvmField
        val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getPreferenceManager)

        @JvmStatic
        fun getInstance(context: Context) = INSTANCE.get(context)!!
    }
}

private fun Int.toFeedbackLevel() = FeedbackLevel.entries.getOrNull(this) ?: FeedbackLevel.DEFAULT

@Composable
fun preferenceManager() = PreferenceManager.getInstance(LocalContext.current)

/**
 * Grid layout configuration for a device's workspace.
 *
 * @param hotseatColumns The amount of column the dock can contain
 * @param workspaceColumns The amount of column the home screen can contain
 * @param workspaceRows The amount of row the home screen can contain
 * @param hotseatColumnsUnfolded The amount of column the dock can contain when unfolded (foldables only)
 */
data class LayoutConfig(
    /**
     * Hotseat columns refer to the amount of column the dock can contain.
     * For foldables, this is the folded (closed) state.
     */
    val hotseatColumns: Int,

    /**
     * Workspace columns refer to the amount of column the home screen can contain.
     */
    val workspaceColumns: Int,

    /**
     * Workspace rows refer to the amount of row the home screen can contain.
     */
    val workspaceRows: Int,

    /**
     * Hotseat columns when the foldable is in unfolded (opened) state.
     * For non-foldable devices, this must be equals to [hotseatColumns].
     */
    val hotseatColumnsUnfolded: Int = hotseatColumns,
)

/** Style, corner radius and background opacity of one long-press menu kind. */
class PopupProfilePrefs(
    val style: BasePreferenceManager.StringPref,
    val radiusDp: BasePreferenceManager.IntPref,
    val opacityPct: BasePreferenceManager.IntPref,
)
