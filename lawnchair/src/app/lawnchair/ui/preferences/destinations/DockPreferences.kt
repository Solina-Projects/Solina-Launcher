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

package app.lawnchair.ui.preferences.destinations

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.DummyLauncherBox
import app.lawnchair.ui.preferences.components.DummyLauncherLayout
import app.lawnchair.ui.preferences.components.WallpaperPreview
import app.lawnchair.ui.preferences.components.WithWallpaper
import app.lawnchair.ui.preferences.components.clipToBottomPercentage
import app.lawnchair.ui.preferences.components.colorpreference.ColorPreference
import app.lawnchair.ui.preferences.components.controls.MainSwitchPreference
import app.lawnchair.ui.preferences.components.controls.SliderPreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.controls.WarningPreference
import app.lawnchair.ui.preferences.components.createPreviewIdp
import app.lawnchair.ui.preferences.components.layout.DividerColumn
import app.lawnchair.ui.preferences.components.layout.ExpandAndShrink
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceGroupHeading
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.R

@Composable
fun DockPreferences(modifier: Modifier = Modifier) {
    val prefs = preferenceManager()
    val prefs2 = preferenceManager2()

    PreferenceLayout(
        label = stringResource(id = R.string.dock_label),
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        val hotseatBgAdapter = prefs.hotseatBG.getAdapter()

        MainSwitchPreference(adapter = prefs2.isHotseatEnabled.getAdapter(), label = stringResource(id = R.string.show_hotseat_title)) {
            DockPreferencesPreview()
            PreferenceGroup(heading = stringResource(id = R.string.style)) {
                SwitchPreference(
                    adapter = hotseatBgAdapter,
                    label = stringResource(id = R.string.hotseat_background),
                )
                ExpandAndShrink(
                    visible = hotseatBgAdapter.state.value,
                ) {
                    HotseatBackgroundSettings(prefs, prefs2)
                }
            }
            SearchBarPreference(SearchRoute.DOCK_SEARCH)
            GridSettings(prefs, prefs2)
            PreferenceGroup(heading = stringResource(id = R.string.icons)) {
                SliderPreference(
                    label = stringResource(id = R.string.icon_sizes),
                    adapter = prefs2.hotseatIconSizeFactor.getAdapter(),
                    step = 0.1f,
                    valueRange = 0.5F..1.5F,
                    showAsPercentage = true,
                )
                val dockLabelsAdapter = prefs2.enableLabelInDock.getAdapter()
                SwitchPreference(
                    adapter = dockLabelsAdapter,
                    label = stringResource(id = R.string.show_labels),
                )
                ExpandAndShrink(visible = dockLabelsAdapter.state.value) {
                    Column {
                        SliderPreference(
                            label = stringResource(id = R.string.dock_label_size),
                            adapter = prefs2.hotseatIconLabelSizeFactor.getAdapter(),
                            step = 0.1f,
                            valueRange = 0.5F..1.5F,
                            showAsPercentage = true,
                        )
                        SliderPreference(
                            label = stringResource(id = R.string.dock_label_vertical_offset),
                            adapter = prefs2.hotseatIconLabelVerticalOffset.getAdapter(),
                            step = 1f,
                            valueRange = -16f..16f,
                            showUnit = "dp",
                        )
                        ColorPreference(preference = prefs2.dockIconLabelColor)
                    }
                }
            }
        }
    }
}

@Composable
fun HotseatBackgroundSettings(prefs: PreferenceManager, prefs2: PreferenceManager2) {
    val presetAdapter = prefs.hotseatBGStylePreset.getAdapter()
    val cornerRadiusAdapter = prefs2.hotseatBackgroundCornerRadius.getAdapter()
    val alphaAdapter = prefs.hotseatBGAlpha.getAdapter()
    val insetLeftAdapter = prefs.hotseatBGHorizontalInsetLeft.getAdapter()
    val insetRightAdapter = prefs.hotseatBGHorizontalInsetRight.getAdapter()
    val insetTopAdapter = prefs.hotseatBGVerticalInsetTop.getAdapter()
    val insetBottomAdapter = prefs.hotseatBGVerticalInsetBottom.getAdapter()
    val glossySheenAdapter = prefs.hotseatBGGlossySheen.getAdapter()
    val glassBorderAdapter = prefs.hotseatBGGlassBorder.getAdapter()
    val borderWidthAdapter = prefs.hotseatBGGlassBorderWidth.getAdapter()

    DividerColumn {
        DockStylePresetBar(
            selectedPreset = presetAdapter.state.value,
            onSelectPreset = { presetId ->
                presetAdapter.onChange(presetId)
                when (presetId) {
                    "floating_glass" -> {
                        cornerRadiusAdapter.onChange(32f)
                        alphaAdapter.onChange(75)
                        insetLeftAdapter.onChange(25)
                        insetRightAdapter.onChange(25)
                        insetTopAdapter.onChange(10)
                        insetBottomAdapter.onChange(10)
                        glossySheenAdapter.onChange(true)
                        glassBorderAdapter.onChange(true)
                        borderWidthAdapter.onChange(2)
                    }

                    "modern_capsule" -> {
                        cornerRadiusAdapter.onChange(42f)
                        alphaAdapter.onChange(90)
                        insetLeftAdapter.onChange(20)
                        insetRightAdapter.onChange(20)
                        insetTopAdapter.onChange(8)
                        insetBottomAdapter.onChange(8)
                        glossySheenAdapter.onChange(false)
                        glassBorderAdapter.onChange(false)
                    }

                    "neon_card" -> {
                        cornerRadiusAdapter.onChange(24f)
                        alphaAdapter.onChange(85)
                        insetLeftAdapter.onChange(15)
                        insetRightAdapter.onChange(15)
                        insetTopAdapter.onChange(5)
                        insetBottomAdapter.onChange(5)
                        glossySheenAdapter.onChange(true)
                        glassBorderAdapter.onChange(true)
                        borderWidthAdapter.onChange(3)
                    }

                    "full_width" -> {
                        cornerRadiusAdapter.onChange(0f)
                        alphaAdapter.onChange(85)
                        insetLeftAdapter.onChange(0)
                        insetRightAdapter.onChange(0)
                        insetTopAdapter.onChange(0)
                        insetBottomAdapter.onChange(0)
                        glossySheenAdapter.onChange(false)
                        glassBorderAdapter.onChange(false)
                    }
                }
            },
        )
        SwitchPreference(
            adapter = glossySheenAdapter,
            label = stringResource(id = R.string.hotseat_bg_glossy_sheen),
            description = stringResource(id = R.string.hotseat_bg_glossy_sheen_summary),
        )
        SwitchPreference(
            adapter = glassBorderAdapter,
            label = stringResource(id = R.string.hotseat_bg_glass_border),
            description = stringResource(id = R.string.hotseat_bg_glass_border_summary),
        )
        ExpandAndShrink(visible = glassBorderAdapter.state.value) {
            SliderPreference(
                label = stringResource(id = R.string.hotseat_bg_glass_border_width),
                adapter = borderWidthAdapter,
                step = 1,
                valueRange = 1..6,
                showUnit = "dp",
            )
        }
        ColorPreference(preference = prefs2.hotseatBackgroundColor)
        SliderPreference(
            label = stringResource(id = R.string.hotseat_bg_corner_radius),
            adapter = cornerRadiusAdapter,
            step = 1f,
            valueRange = 0f..100f,
            showUnit = "dp",
        )
        SliderPreference(
            label = stringResource(id = R.string.hotseat_bg_alpha),
            adapter = alphaAdapter,
            step = 5,
            valueRange = 5..100,
            showUnit = "%",
        )
        SliderPreference(
            label = stringResource(id = R.string.hotseat_bg_horizontal_inset_left),
            adapter = insetLeftAdapter,
            step = 5,
            valueRange = 0..100,
            showUnit = "px",
        )
        SliderPreference(
            label = stringResource(id = R.string.hotseat_bg_horizontal_inset_right),
            adapter = insetRightAdapter,
            step = 5,
            valueRange = 0..100,
            showUnit = "px",
        )
        SliderPreference(
            label = stringResource(id = R.string.hotseat_bg_vertical_inset_top),
            adapter = insetTopAdapter,
            step = 5,
            valueRange = 0..100,
            showUnit = "px",
        )
        SliderPreference(
            label = stringResource(id = R.string.hotseat_bg_vertical_inset_bottom),
            adapter = insetBottomAdapter,
            step = 5,
            valueRange = 0..100,
            showUnit = "px",
        )
    }
}

@Composable
private fun DockStylePresetBar(
    selectedPreset: String,
    onSelectPreset: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = listOf(
        "floating_glass" to stringResource(id = R.string.hotseat_preset_floating_glass),
        "modern_capsule" to stringResource(id = R.string.hotseat_preset_modern_capsule),
        "neon_card" to stringResource(id = R.string.hotseat_preset_neon_card),
        "full_width" to stringResource(id = R.string.hotseat_preset_full_width),
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(id = R.string.hotseat_style_presets_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { (id, title) ->
                val isSelected = selectedPreset == id
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        },
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelectPreset(id) },
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun GridSettings(prefs: PreferenceManager, prefs2: PreferenceManager2) {
    val isFoldable = InvariantDeviceProfile.deviceType == InvariantDeviceProfile.TYPE_MULTI_DISPLAY
    val hotseatColumnsAdapter = prefs.hotseatColumns.getAdapter()
    val hotseatColumnsUnfoldedAdapter = prefs.hotseatColumnsUnfolded.getAdapter()
    val hotseatRowsAdapter = prefs.hotseatRows.getAdapter()
    val dockPagesAdapter = prefs.dockPages.getAdapter()

    PreferenceGroup(heading = stringResource(id = R.string.grid)) {
        if (isFoldable) {
            SliderPreference(
                label = stringResource(id = R.string.state_folded, stringResource(id = R.string.dock_icons)),
                adapter = hotseatColumnsAdapter,
                step = 1,
                valueRange = 3..10,
            )
            SliderPreference(
                label = stringResource(id = R.string.state_unfolded, stringResource(id = R.string.dock_icons)),
                adapter = hotseatColumnsUnfoldedAdapter,
                step = 1,
                valueRange = 3..10,
            )
            ExpandAndShrink(
                visible = hotseatColumnsAdapter.state.value > hotseatColumnsUnfoldedAdapter.state.value,
            ) {
                WarningPreference(
                    text = stringResource(id = R.string.foldable_columns_error),
                )
            }
        } else {
            SliderPreference(
                label = stringResource(id = R.string.dock_icons),
                adapter = hotseatColumnsAdapter,
                step = 1,
                valueRange = 3..10,
            )
        }
        SliderPreference(
            label = stringResource(id = R.string.dock_rows),
            adapter = hotseatRowsAdapter,
            step = 1,
            valueRange = 1..3,
        )
        // These two controls remain visible and keep their values for every dock row count.
        SliderPreference(
            label = stringResource(id = R.string.dock_vertical_padding),
            adapter = prefs2.hotseatRowSpaceFactor.getAdapter(),
            step = 0.05f,
            valueRange = 0.8f..1.5f,
            showAsPercentage = true,
        )
        SliderPreference(
            label = stringResource(id = R.string.dock_icon_spacing),
            adapter = prefs2.hotseatIconSpacingFactor.getAdapter(),
            step = 0.05f,
            valueRange = 0.8f..1.5f,
            showAsPercentage = true,
        )
        SliderPreference(
            label = stringResource(id = R.string.dock_pages),
            adapter = dockPagesAdapter,
            step = 1,
            valueRange = 1..5,
        )
        SliderPreference(
            adapter = prefs2.hotseatBottomFactor.getAdapter(),
            label = stringResource(id = R.string.hotseat_bottom_space_label),
            valueRange = 0.0F..1.7F,
            step = 0.1F,
            showAsPercentage = true,
        )
        SliderPreference(
            adapter = prefs2.pageIndicatorHeightFactor.getAdapter(),
            label = stringResource(id = R.string.page_indicator_height),
            valueRange = 0.0F..1.0F,
            step = 0.1F,
            showAsPercentage = true,
        )
    }
}

@Composable
fun ColumnScope.DockPreferencesPreview(modifier: Modifier = Modifier) {
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT) {
        val prefs = preferenceManager()
        val prefs2 = preferenceManager2()

        val hotseatRows = prefs.hotseatRows
        val dockPages = prefs.dockPages

        val adapters = listOf(
            prefs2.hotseatMode.getAdapter(),
            prefs.hotseatColumns.getAdapter(),
            prefs.hotseatColumnsUnfolded.getAdapter(),
            hotseatRows.getAdapter(),
            dockPages.getAdapter(),
            prefs2.themedHotseatQsb.getAdapter(),
            prefs.hotseatQsbCornerRadius.getAdapter(),
            prefs.hotseatQsbAlpha.getAdapter(),
            prefs.hotseatQsbStrokeWidth.getAdapter(),
            prefs2.hotseatBottomFactor.getAdapter(),
            prefs2.hotseatRowSpaceFactor.getAdapter(),
            prefs2.hotseatIconSpacingFactor.getAdapter(),
            prefs2.strokeColorStyle.getAdapter(),
            prefs2.enableLabelInDock.getAdapter(),
            prefs.hotseatBG.getAdapter(),
            prefs.hotseatBGHorizontalInsetLeft.getAdapter(),
            prefs.hotseatBGVerticalInsetTop.getAdapter(),
            prefs.hotseatBGHorizontalInsetRight.getAdapter(),
            prefs.hotseatBGVerticalInsetBottom.getAdapter(),
            prefs2.pageIndicatorHeightFactor.getAdapter(),
            prefs2.hotseatBackgroundColor.getAdapter(),
            prefs2.hotseatBackgroundCornerRadius.getAdapter(),
            prefs.hotseatBGAlpha.getAdapter(),
            prefs.hotseatBGStylePreset.getAdapter(),
            prefs.hotseatBGGlossySheen.getAdapter(),
            prefs.hotseatBGGlassBorder.getAdapter(),
            prefs.hotseatBGGlassBorderWidth.getAdapter(),
        )

        PreferenceGroupHeading(
            heading = stringResource(id = R.string.preview_label),
        )
        DividerColumn(
            modifier = modifier.padding(horizontal = 16.dp),
        ) {
            WithWallpaper { wallpaper ->
                DummyLauncherBox(
                    modifier = Modifier
                        .weight(1f)
                        .align(Alignment.CenterHorizontally)
                        .clip(MaterialTheme.shapes.large)
                        .clipToBottomPercentage(
                            when (hotseatRows.getAdapter().state.value) {
                                3 -> 0.48f
                                2 -> 0.4f
                                else -> 0.3f
                            },
                        ),
                ) {
                    WallpaperPreview(
                        wallpaper = wallpaper,
                        modifier = Modifier.fillMaxSize(),
                    )
                    key(adapters.map { it.state.value }.toTypedArray()) {
                        DummyLauncherLayout(
                            idp = createPreviewIdp { copy(numHotseatColumns = prefs.hotseatColumns.get()) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
