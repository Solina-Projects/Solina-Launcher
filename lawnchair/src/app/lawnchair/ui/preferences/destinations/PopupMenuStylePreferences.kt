package app.lawnchair.ui.preferences.destinations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.Preferences
import app.lawnchair.preferences.PopupProfilePrefs
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.theme.color.ColorOption
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.colorpreference.ColorPreference
import app.lawnchair.ui.preferences.components.controls.ListPreference
import app.lawnchair.ui.preferences.components.controls.ListPreferenceEntry
import app.lawnchair.ui.preferences.components.controls.SliderPreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.util.PopupKind
import com.android.launcher3.R
import com.patrykmichalik.opto.domain.Preference

private typealias ColorPref = Preference<ColorOption, String, Preferences.Key<String>>

/**
 * General > Pop-up menus. The single place that controls how long-press menus look: background,
 * text color, opacity, corner shape and design, either for all menus at once (linked) or
 * separately for the home screen menu and for apps in the drawer, on the home screen and in the dock.
 */
@Composable
fun PopupMenuStylePreferences(modifier: Modifier = Modifier) {
    val prefs = preferenceManager()
    val prefs2 = preferenceManager2()
    val linkedAdapter = prefs.popupMenuLinked.getAdapter()

    PreferenceLayout(
        label = stringResource(R.string.popup_menu_style_title),
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        PreferenceGroup {
            SwitchPreference(
                adapter = linkedAdapter,
                label = stringResource(R.string.popup_menu_link_label),
                description = stringResource(R.string.popup_menu_link_desc),
            )
        }

        if (linkedAdapter.state.value) {
            PopupStyleGroup(
                heading = stringResource(R.string.popup_menu_all_menus),
                profile = prefs.popupSharedProfile,
                background = prefs2.popupSharedBackgroundColor,
                text = prefs2.popupSharedTextColor,
            )
        } else {
            for (kind in PopupKind.entries) {
                PopupStyleGroup(
                    heading = stringResource(kind.labelRes()),
                    description = if (kind == PopupKind.HOME_MENU) stringResource(R.string.popup_kind_home_menu_desc) else null,
                    profile = prefs.popupProfiles.getValue(kind.id),
                    background = prefs2.backgroundColorFor(kind),
                    text = prefs2.textColorFor(kind),
                )
            }
        }
    }
}

@Composable
private fun PopupStyleGroup(
    heading: String,
    profile: PopupProfilePrefs,
    background: ColorPref,
    text: ColorPref,
    description: String? = null,
) {
    val styleEntries = remember {
        listOf(
            ListPreferenceEntry("liquid_glass") { stringResource(R.string.style_option_liquid_glass) },
            ListPreferenceEntry("glass_card") { stringResource(R.string.popup_style_glass_card) },
            ListPreferenceEntry("elevated_3d") { stringResource(R.string.popup_style_elevated_3d) },
            ListPreferenceEntry("neon_border") { stringResource(R.string.popup_style_neon_border) },
            ListPreferenceEntry("classic") { stringResource(R.string.popup_style_classic) },
        )
    }
    PreferenceGroup(heading = heading, description = description) {
        ListPreference(
            adapter = profile.style.getAdapter(),
            entries = styleEntries,
            label = stringResource(R.string.popup_surface_style_label),
        )
        ColorPreference(preference = background)
        ColorPreference(preference = text)
        SliderPreference(
            label = stringResource(R.string.popup_bg_opacity_label),
            adapter = profile.opacityPct.getAdapter(),
            step = 2,
            valueRange = 20..100,
            showUnit = "%",
        )
        SliderPreference(
            label = stringResource(R.string.popup_corner_radius_label),
            adapter = profile.radiusDp.getAdapter(),
            step = 2,
            valueRange = 12..36,
            showUnit = "dp",
        )
    }
}

private fun PopupKind.labelRes(): Int = when (this) {
    PopupKind.HOME_MENU -> R.string.popup_kind_home_menu
    PopupKind.APP_DRAWER -> R.string.popup_kind_app_drawer
    PopupKind.APP_HOME -> R.string.popup_kind_app_home
    PopupKind.APP_DOCK -> R.string.popup_kind_app_dock
}

private fun PreferenceManager2.backgroundColorFor(kind: PopupKind): ColorPref = when (kind) {
    PopupKind.HOME_MENU -> popupHomeMenuBackgroundColor
    PopupKind.APP_DRAWER -> popupAppDrawerBackgroundColor
    PopupKind.APP_HOME -> popupAppHomeBackgroundColor
    PopupKind.APP_DOCK -> popupAppDockBackgroundColor
}

private fun PreferenceManager2.textColorFor(kind: PopupKind): ColorPref = when (kind) {
    PopupKind.HOME_MENU -> popupHomeMenuTextColor
    PopupKind.APP_DRAWER -> popupAppDrawerTextColor
    PopupKind.APP_HOME -> popupAppHomeTextColor
    PopupKind.APP_DOCK -> popupAppDockTextColor
}
