package app.lawnchair.ui.preferences.destinations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lawnchair.glass.LiquidGlassConfig
import app.lawnchair.glass.LiquidGlassStyleHelper
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.observeAsState
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.theme.color.tokens.ColorTokens
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.controls.ListPreference
import app.lawnchair.ui.preferences.components.controls.ListPreferenceEntry
import app.lawnchair.ui.preferences.components.controls.MainSwitchPreference
import app.lawnchair.ui.preferences.components.controls.SliderPreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import com.android.launcher3.R

@Composable
fun LiquidGlassPreferences(
    modifier: Modifier = Modifier,
) {
    val prefs = preferenceManager()
    val enabledAdapter = prefs.liquidGlassEnabled.getAdapter()

    val preset by prefs.liquidGlassPreset.observeAsState()
    val tintSwatch by prefs.liquidGlassTintSwatch.observeAsState()
    val density by prefs.liquidGlassDensity.observeAsState()
    val blurPx by prefs.liquidGlassBlurPx.observeAsState()
    val rimLight by prefs.liquidGlassRimLight.observeAsState()
    val bevel by prefs.liquidGlassBevel.observeAsState()
    val refraction by prefs.liquidGlassRefraction.observeAsState()
    val dispersion by prefs.liquidGlassDispersion.observeAsState()
    val rimWidthDp by prefs.liquidGlassRimWidthDp.observeAsState()
    val shadow by prefs.liquidGlassShadow.observeAsState()

    val liveConfig = remember(
        enabledAdapter.state.value,
        preset,
        tintSwatch,
        density,
        blurPx,
        rimLight,
        bevel,
        refraction,
        dispersion,
        rimWidthDp,
        shadow,
    ) {
        LiquidGlassConfig(
            enabled = enabledAdapter.state.value,
            preset = preset,
            tintSwatch = tintSwatch,
            density = density,
            blurPx = blurPx,
            rimLight = rimLight,
            bevel = bevel,
            refraction = refraction,
            dispersion = dispersion,
            rimWidthDp = rimWidthDp,
            shadow = shadow,
        )
    }

    val swatchEntries = remember {
        listOf(
            ListPreferenceEntry("auto_theme") { stringResource(R.string.liquid_glass_swatch_auto) },
            ListPreferenceEntry("accent") { stringResource(R.string.liquid_glass_swatch_accent) },
            ListPreferenceEntry("clear_white") { stringResource(R.string.liquid_glass_swatch_clear) },
            ListPreferenceEntry("warm_sand") { stringResource(R.string.liquid_glass_swatch_warm) },
            ListPreferenceEntry("sky_ice") { stringResource(R.string.liquid_glass_swatch_sky) },
            ListPreferenceEntry("rose_quartz") { stringResource(R.string.liquid_glass_swatch_rose) },
            ListPreferenceEntry("mint_jade") { stringResource(R.string.liquid_glass_swatch_mint) },
            ListPreferenceEntry("lilac_mist") { stringResource(R.string.liquid_glass_swatch_lilac) },
            ListPreferenceEntry("obsidian_night") { stringResource(R.string.liquid_glass_swatch_obsidian) },
            ListPreferenceEntry("ocean_deep") { stringResource(R.string.liquid_glass_swatch_ocean) },
        )
    }

    PreferenceLayout(
        label = stringResource(id = R.string.liquid_glass_studio_title),
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        MainSwitchPreference(
            adapter = enabledAdapter,
            label = stringResource(id = R.string.liquid_glass_master_enable_label),
            description = stringResource(id = R.string.liquid_glass_master_enable_desc),
        ) {
            LiquidGlassLivePreviewCard(
                config = liveConfig,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            PreferenceGroup(heading = stringResource(id = R.string.liquid_glass_presets_heading)) {
                LiquidGlassPresetRow(
                    selectedPreset = preset,
                    onSelectPreset = { selectedId ->
                        LiquidGlassStyleHelper.applyPreset(prefs, selectedId)
                    },
                )
                ListPreference(
                    adapter = prefs.liquidGlassTintSwatch.getAdapter(),
                    entries = swatchEntries,
                    label = stringResource(id = R.string.liquid_glass_tint_swatch_label),
                )
            }

            PreferenceGroup(heading = stringResource(id = R.string.liquid_glass_optics_heading)) {
                SliderPreference(
                    label = stringResource(id = R.string.liquid_glass_density_label),
                    adapter = prefs.liquidGlassDensity.getAdapter(),
                    step = 0.05f,
                    valueRange = 0.05f..0.95f,
                    showAsPercentage = true,
                )
                SliderPreference(
                    label = stringResource(id = R.string.liquid_glass_blur_label),
                    adapter = prefs.liquidGlassBlurPx.getAdapter(),
                    step = 2,
                    valueRange = 0..40,
                    showUnit = "px",
                )
                SliderPreference(
                    label = stringResource(id = R.string.liquid_glass_rim_light_label),
                    adapter = prefs.liquidGlassRimLight.getAdapter(),
                    step = 0.05f,
                    valueRange = 0.0f..2.0f,
                    showAsPercentage = true,
                )
                SliderPreference(
                    label = stringResource(id = R.string.liquid_glass_bevel_label),
                    adapter = prefs.liquidGlassBevel.getAdapter(),
                    step = 0.1f,
                    valueRange = 0.0f..2.5f,
                    showAsPercentage = true,
                )
                SliderPreference(
                    label = stringResource(id = R.string.liquid_glass_refraction_label),
                    adapter = prefs.liquidGlassRefraction.getAdapter(),
                    step = 0.02f,
                    valueRange = 0.0f..0.40f,
                    showAsPercentage = true,
                )
                SliderPreference(
                    label = stringResource(id = R.string.liquid_glass_dispersion_label),
                    adapter = prefs.liquidGlassDispersion.getAdapter(),
                    step = 0.02f,
                    valueRange = 0.0f..0.40f,
                    showAsPercentage = true,
                )
                SliderPreference(
                    label = stringResource(id = R.string.liquid_glass_rim_width_label),
                    adapter = prefs.liquidGlassRimWidthDp.getAdapter(),
                    step = 4,
                    valueRange = 8..64,
                    showUnit = "dp",
                )
                SliderPreference(
                    label = stringResource(id = R.string.liquid_glass_shadow_label),
                    adapter = prefs.liquidGlassShadow.getAdapter(),
                    step = 0.1f,
                    valueRange = 0.0f..2.0f,
                    showAsPercentage = true,
                )
            }

            PreferenceGroup(heading = stringResource(id = R.string.liquid_glass_surfaces_heading)) {
                SwitchPreference(
                    adapter = prefs.liquidGlassApplyDock.getAdapter(),
                    label = stringResource(id = R.string.liquid_glass_apply_dock_label),
                    description = stringResource(id = R.string.liquid_glass_apply_dock_desc),
                )
                SwitchPreference(
                    adapter = prefs.liquidGlassApplyQsb.getAdapter(),
                    label = stringResource(id = R.string.liquid_glass_apply_qsb_label),
                    description = stringResource(id = R.string.liquid_glass_apply_qsb_desc),
                )
                SwitchPreference(
                    adapter = prefs.liquidGlassApplySmartspace.getAdapter(),
                    label = stringResource(id = R.string.liquid_glass_apply_smartspace_label),
                    description = stringResource(id = R.string.liquid_glass_apply_smartspace_desc),
                )
                SwitchPreference(
                    adapter = prefs.liquidGlassApplyDrawer.getAdapter(),
                    label = stringResource(id = R.string.liquid_glass_apply_drawer_label),
                    description = stringResource(id = R.string.liquid_glass_apply_drawer_desc),
                )
                SwitchPreference(
                    adapter = prefs.liquidGlassApplySuggestions.getAdapter(),
                    label = stringResource(id = R.string.liquid_glass_apply_suggestions_label),
                    description = stringResource(id = R.string.liquid_glass_apply_suggestions_desc),
                )
                SwitchPreference(
                    adapter = prefs.liquidGlassApplySearchCards.getAdapter(),
                    label = stringResource(id = R.string.liquid_glass_apply_search_cards_label),
                    description = stringResource(id = R.string.liquid_glass_apply_search_cards_desc),
                )
                SwitchPreference(
                    adapter = prefs.liquidGlassApplyFoldersPopups.getAdapter(),
                    label = stringResource(id = R.string.liquid_glass_apply_folders_popups_label),
                    description = stringResource(id = R.string.liquid_glass_apply_folders_popups_desc),
                )
                SwitchPreference(
                    adapter = prefs.liquidGlassApplyRecents.getAdapter(),
                    label = stringResource(id = R.string.liquid_glass_apply_recents_label),
                    description = stringResource(id = R.string.liquid_glass_apply_recents_desc),
                )
            }
        }
    }
}

@Composable
private fun LiquidGlassLivePreviewCard(
    config: LiquidGlassConfig,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isDark = LiquidGlassStyleHelper.isDarkConfiguration(context)
    val accentColor = remember(context) { ColorTokens.ColorAccent.resolveColor(context) }
    val baseTint = remember(config.tintSwatch, isDark, accentColor) {
        LiquidGlassStyleHelper.resolveTintColor(config.tintSwatch, isDark, 0, accentColor)
    }
    val previewTitle = stringResource(id = R.string.liquid_glass_preview_card_title)
    val previewSubtitle = stringResource(
        id = R.string.liquid_glass_preview_card_metrics,
        (config.rimLight * 100).toInt(),
        (config.refraction * 100).toInt(),
        (config.dispersion * 100).toInt(),
    )
    val searchHint = stringResource(id = R.string.liquid_glass_preview_search_hint)

    Surface(
        shape = RoundedCornerShape(28.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(232.dp)
            .clip(RoundedCornerShape(28.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF0F172A),
                            Color(0xFF1E1B4B),
                            Color(0xFF0D9488),
                            Color(0xFF4C1D95),
                        ),
                    ),
                ),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Colorful backdrop orbs so optical refraction, bevel, and rim light stand out vividly
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF38BDF8).copy(alpha = 0.72f), Color.Transparent),
                        center = Offset(size.width * 0.22f, size.height * 0.28f),
                        radius = size.minDimension * 0.45f,
                    ),
                    radius = size.minDimension * 0.45f,
                    center = Offset(size.width * 0.22f, size.height * 0.28f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFF43F5E).copy(alpha = 0.65f), Color.Transparent),
                        center = Offset(size.width * 0.78f, size.height * 0.36f),
                        radius = size.minDimension * 0.42f,
                    ),
                    radius = size.minDimension * 0.42f,
                    center = Offset(size.width * 0.78f, size.height * 0.36f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFFBBF24).copy(alpha = 0.55f), Color.Transparent),
                        center = Offset(size.width * 0.50f, size.height * 0.78f),
                        radius = size.minDimension * 0.48f,
                    ),
                    radius = size.minDimension * 0.48f,
                    center = Offset(size.width * 0.50f, size.height * 0.78f),
                )

                val densityScale = density
                val hPad = 20.dp.toPx()

                // 1. Top Liquid Glass Slab Card
                val cardTop = 16.dp.toPx()
                val cardBottom = 96.dp.toPx()
                val cardRadius = 24.dp.toPx()
                val cardDrawable = app.lawnchair.glass.LiquidGlassDrawable(
                    config = config,
                    baseTintColor = baseTint,
                    accentColor = accentColor,
                    isDarkTheme = isDark,
                    densityScale = densityScale,
                    cornerRadiusPx = cardRadius,
                )
                cardDrawable.setBounds(
                    hPad.toInt(),
                    cardTop.toInt(),
                    (size.width - hPad).toInt(),
                    cardBottom.toInt(),
                )

                // 2. Middle Liquid Glass Search Pill
                val qsbTop = 108.dp.toPx()
                val qsbBottom = 152.dp.toPx()
                val qsbRadius = 22.dp.toPx()
                val qsbDrawable = app.lawnchair.glass.LiquidGlassDrawable(
                    config = config,
                    baseTintColor = baseTint,
                    accentColor = accentColor,
                    isDarkTheme = isDark,
                    densityScale = densityScale,
                    cornerRadiusPx = qsbRadius,
                )
                qsbDrawable.setBounds(
                    (hPad + 8.dp.toPx()).toInt(),
                    qsbTop.toInt(),
                    (size.width - hPad - 8.dp.toPx()).toInt(),
                    qsbBottom.toInt(),
                )

                // 3. Bottom Liquid Glass Dock Bar
                val dockTop = 164.dp.toPx()
                val dockBottom = 216.dp.toPx()
                val dockRadius = 26.dp.toPx()
                val dockDrawable = app.lawnchair.glass.LiquidGlassDrawable(
                    config = config,
                    baseTintColor = baseTint,
                    accentColor = accentColor,
                    isDarkTheme = isDark,
                    densityScale = densityScale,
                    cornerRadiusPx = dockRadius,
                )
                dockDrawable.setBounds(
                    hPad.toInt(),
                    dockTop.toInt(),
                    (size.width - hPad).toInt(),
                    dockBottom.toInt(),
                )

                drawIntoCanvas { canvas ->
                    cardDrawable.draw(canvas.nativeCanvas)
                    qsbDrawable.draw(canvas.nativeCanvas)
                    dockDrawable.draw(canvas.nativeCanvas)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 34.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = previewTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = previewSubtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "G   $searchHint",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.90f),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val iconColors = listOf(
                        Color(0xFF38BDF8),
                        Color(0xFF34D399),
                        Color(0xFFF472B6),
                        Color(0xFFFBBF24),
                    )
                    iconColors.forEach { dotColor ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(dotColor.copy(alpha = 0.88f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiquidGlassPresetRow(
    selectedPreset: String,
    onSelectPreset: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = listOf(
        "lumno" to stringResource(id = R.string.liquid_glass_preset_lumno),
        "clear" to stringResource(id = R.string.liquid_glass_preset_clear),
        "frosted" to stringResource(id = R.string.liquid_glass_preset_frosted),
        "thick_slab" to stringResource(id = R.string.liquid_glass_preset_thick_slab),
        "vivid" to stringResource(id = R.string.liquid_glass_preset_vivid),
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { (id, label) ->
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
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelectPreset(id) },
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}
