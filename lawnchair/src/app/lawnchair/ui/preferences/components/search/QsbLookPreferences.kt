package app.lawnchair.ui.preferences.components.search

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.components.colorpreference.ColorPreference
import app.lawnchair.ui.preferences.components.controls.ListPreference
import app.lawnchair.ui.preferences.components.controls.ListPreferenceEntry
import app.lawnchair.ui.preferences.components.controls.SliderPreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.ExpandAndShrink
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import com.android.launcher3.R

/**
 * Look of the search bars (surface style, 3D effect, 3D icons, hint text, end icon pill, and colors).
 * The settings are shared, so they apply to both the dock and the app drawer search bar.
 */
@Composable
fun QsbLookPreferences(
    modifier: Modifier = Modifier,
) {
    val prefs = preferenceManager()
    val prefs2 = preferenceManager2()
    val surfaceStyleAdapter = prefs.qsbSurfaceStyle.getAdapter()
    val enabledAdapter = prefs2.qsb3dEnabled.getAdapter()

    val styleEntries = listOf(
        ListPreferenceEntry("3d_bevel") { stringResource(R.string.qsb_surface_style_3d_bevel) },
        ListPreferenceEntry("glass_capsule") { stringResource(R.string.qsb_surface_style_glass_capsule) },
        ListPreferenceEntry("neon_border") { stringResource(R.string.qsb_surface_style_neon_border) },
        ListPreferenceEntry("minimal_flat") { stringResource(R.string.qsb_surface_style_minimal_flat) },
    )

    PreferenceGroup(
        heading = stringResource(R.string.qsb_style_heading),
        modifier = modifier,
    ) {
        ListPreference(
            adapter = surfaceStyleAdapter,
            entries = styleEntries,
            label = stringResource(R.string.qsb_surface_style_label),
        )
        ExpandAndShrink(visible = surfaceStyleAdapter.state.value == "3d_bevel") {
            Column {
                SwitchPreference(
                    adapter = enabledAdapter,
                    label = stringResource(R.string.qsb_3d_enabled),
                )
                ExpandAndShrink(visible = enabledAdapter.state.value) {
                    Column {
                        SliderPreference(
                            label = stringResource(R.string.qsb_3d_depth),
                            adapter = prefs2.qsb3dDepth.getAdapter(),
                            step = 1f,
                            valueRange = 1f..8f,
                            showUnit = "dp",
                        )
                    }
                }
            }
        }
        SwitchPreference(
            adapter = prefs2.qsbIcons3d.getAdapter(),
            label = stringResource(R.string.qsb_icons_3d),
        )
        SwitchPreference(
            adapter = prefs.qsbShowHintText.getAdapter(),
            label = stringResource(R.string.qsb_show_hint_text_label),
            description = stringResource(R.string.qsb_show_hint_text_summary),
        )
        SwitchPreference(
            adapter = prefs.qsbEndIconPillBg.getAdapter(),
            label = stringResource(R.string.qsb_end_icon_pill_bg_label),
            description = stringResource(R.string.qsb_end_icon_pill_bg_summary),
        )
        ColorPreference(preference = prefs2.qsbBackgroundColor)
        ColorPreference(preference = prefs2.qsbIconColor)
    }
}
