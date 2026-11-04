package app.lawnchair.ui.preferences.components.search

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.components.colorpreference.ColorPreference
import app.lawnchair.ui.preferences.components.controls.SliderPreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.ExpandAndShrink
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import com.android.launcher3.R

/**
 * Look of the search bars (3D effect, 3D icons and colors). The settings are shared, so they
 * apply to both the dock and the app drawer search bar.
 */
@Composable
fun QsbLookPreferences() {
    val prefs2 = preferenceManager2()
    val enabledAdapter = prefs2.qsb3dEnabled.getAdapter()

    PreferenceGroup(heading = stringResource(R.string.qsb_style_heading)) {
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
        SwitchPreference(
            adapter = prefs2.qsbIcons3d.getAdapter(),
            label = stringResource(R.string.qsb_icons_3d),
        )
        ColorPreference(preference = prefs2.qsbBackgroundColor)
        ColorPreference(preference = prefs2.qsbIconColor)
    }
}
