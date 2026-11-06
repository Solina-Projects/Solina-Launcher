package app.lawnchair.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import app.lawnchair.glass.LiquidGlassStyleHelper
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.theme.color.tokens.ColorTokens
import kotlin.math.max
import kotlin.math.roundToInt

object FolderPopupStyleHelper {

    @JvmStatic
    fun applyOpenFolderBackground(
        context: Context,
        background: GradientDrawable,
    ) {
        val prefs = PreferenceManager.getInstance(context)
        val density = context.resources.displayMetrics.density
        val baseColor = resolveFolderBackgroundColor(context)
        val alpha = getFolderBackgroundAlpha(context)
        val style = prefs.folderSurfaceStyle.get()
        val radiusDp = prefs.folderCornerRadiusDp.get().coerceIn(12, 44)
        val radiusPx = radiusDp * density

        background.mutate()
        background.cornerRadius = radiusPx

        val accentColor = ColorTokens.ColorAccent.resolveColor(context)
        if (LiquidGlassStyleHelper.shouldUseForFoldersPopups(context, style)) {
            // Keep mBackground lightweight during morph and let drawOpenFolderOverlay render full Liquid Glass layers
            val isDark = LiquidGlassStyleHelper.isDarkConfiguration(context)
            val config = LiquidGlassStyleHelper.getConfig(context)
            val tint = LiquidGlassStyleHelper.resolveTintColor(config.tintSwatch, isDark, baseColor, accentColor)
            val glassAlpha = (alpha * 0.25f).roundToInt().coerceIn(16, 90)
            background.setColor(ColorUtils.setAlphaComponent(tint, glassAlpha))
            background.setStroke(0, Color.TRANSPARENT)
            return
        }

        when (style) {
            "glass_card" -> {
                val topColor = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(baseColor, Color.WHITE, 0.18f),
                    alpha,
                )
                val bottomColor = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(baseColor, accentColor, 0.08f),
                    (alpha * 0.90f).roundToInt().coerceIn(0, 255),
                )
                background.orientation = GradientDrawable.Orientation.TOP_BOTTOM
                background.colors = intArrayOf(topColor, bottomColor)
                val borderAlpha = (alpha * 0.55f).roundToInt().coerceIn(40, 175)
                background.setStroke(
                    max(1, (1.3f * density).roundToInt()),
                    ColorUtils.setAlphaComponent(Color.WHITE, borderAlpha),
                )
            }

            "elevated_3d" -> {
                val topColor = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(baseColor, Color.WHITE, 0.24f),
                    alpha,
                )
                val bottomColor = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(baseColor, Color.BLACK, 0.16f),
                    alpha,
                )
                background.orientation = GradientDrawable.Orientation.TOP_BOTTOM
                background.colors = intArrayOf(topColor, bottomColor)
                background.setStroke(
                    max(1, (1.6f * density).roundToInt()),
                    ColorUtils.setAlphaComponent(Color.WHITE, (alpha * 0.65f).roundToInt().coerceIn(50, 200)),
                )
            }

            "neon_border" -> {
                background.setColor(ColorUtils.setAlphaComponent(baseColor, alpha))
                background.setStroke(
                    max(2, (2f * density).roundToInt()),
                    ColorUtils.setAlphaComponent(accentColor, max(160, alpha)),
                )
            }

            else -> {
                // "classic"
                background.setColor(baseColor)
                background.alpha = alpha
                background.setStroke(0, Color.TRANSPARENT)
            }
        }
    }

    @JvmStatic
    fun drawOpenFolderOverlay(
        context: Context,
        canvas: Canvas,
        bounds: Rect,
    ) {
        val prefs = PreferenceManager.getInstance(context)
        val style = prefs.folderSurfaceStyle.get()
        if (!LiquidGlassStyleHelper.shouldUseForFoldersPopups(context, style)) return
        val density = context.resources.displayMetrics.density
        val radiusPx = prefs.folderCornerRadiusDp.get().coerceIn(12, 44) * density
        val baseColor = resolveFolderBackgroundColor(context)
        val alphaMultiplier = (getFolderBackgroundAlpha(context) / 255f).coerceIn(0.25f, 1.0f)
        LiquidGlassStyleHelper.drawOnCanvasBounds(
            context = context,
            canvas = canvas,
            bounds = bounds,
            cornerRadiusPx = radiusPx,
            fallbackColor = baseColor,
            alphaMultiplier = alphaMultiplier,
        )
    }

    @JvmStatic
    fun resolvePopupOutlineRadius(
        context: Context,
        defaultRadius: Float,
    ): Float {
        val prefs = PreferenceManager.getInstance(context)
        val style = prefs.popupSurfaceStyle.get()
        if (style == "classic" && !LiquidGlassStyleHelper.shouldUseForFoldersPopups(context, style)) {
            return defaultRadius
        }
        val density = context.resources.displayMetrics.density
        return prefs.popupCornerRadiusDp.get().coerceIn(12, 36) * density
    }

    @JvmStatic
    fun applyPopupAlphaToColor(
        context: Context,
        baseColor: Int,
    ): Int {
        val prefs = PreferenceManager.getInstance(context)
        val opacityPct = prefs.popupBgOpacity.get().coerceIn(20, 100)
        val alpha = (opacityPct / 100f * 255f).roundToInt().coerceIn(51, 255)
        return ColorUtils.setAlphaComponent(baseColor, alpha)
    }

    @JvmStatic
    fun applyPopupItemDrawable(
        context: Context,
        drawable: GradientDrawable,
        baseColor: Int,
    ) {
        val prefs = PreferenceManager.getInstance(context)
        val style = prefs.popupSurfaceStyle.get()
        val opacityPct = prefs.popupBgOpacity.get().coerceIn(20, 100)
        val alpha = (opacityPct / 100f * 255f).roundToInt().coerceIn(51, 255)
        val density = context.resources.displayMetrics.density
        val accentColor = ColorTokens.ColorAccent.resolveColor(context)
        val opaqueBase = ColorUtils.setAlphaComponent(baseColor, 255)

        if (LiquidGlassStyleHelper.shouldUseForFoldersPopups(context, style)) {
            val config = LiquidGlassStyleHelper.getConfig(context)
            val isDark = LiquidGlassStyleHelper.isDarkConfiguration(context)
            val tint = ColorUtils.setAlphaComponent(
                LiquidGlassStyleHelper.resolveTintColor(config.tintSwatch, isDark, opaqueBase, accentColor),
                255,
            )
            // Blend tint into baseColor while strictly honoring the user's popupBgOpacity slider (100% -> alpha 255)
            val tintStrength = if (opacityPct >= 98) 0.22f else 0.48f
            val blendedColor = ColorUtils.blendARGB(opaqueBase, tint, tintStrength)
            drawable.colors = null
            drawable.setColor(ColorUtils.setAlphaComponent(blendedColor, alpha))
            drawable.alpha = 255
            val catchAlpha = (145f * config.rimLight).roundToInt().coerceIn(45, 235)
            val rimColor = if (config.dispersion > 0.12f) {
                ColorUtils.blendARGB(Color.WHITE, accentColor, 0.28f)
            } else {
                Color.WHITE
            }
            drawable.setStroke(
                max(1, (1.4f * density).roundToInt()),
                ColorUtils.setAlphaComponent(rimColor, catchAlpha),
            )
            return
        }

        drawable.alpha = 255
        when (style) {
            "glass_card" -> {
                val topColor = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(opaqueBase, Color.WHITE, 0.14f),
                    alpha,
                )
                val bottomColor = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(opaqueBase, accentColor, 0.08f),
                    alpha,
                )
                drawable.orientation = GradientDrawable.Orientation.TOP_BOTTOM
                drawable.colors = intArrayOf(topColor, bottomColor)
                drawable.setStroke(
                    max(1, (1.1f * density).roundToInt()),
                    ColorUtils.setAlphaComponent(Color.WHITE, 95),
                )
            }

            "elevated_3d" -> {
                val topColor = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(opaqueBase, Color.WHITE, 0.16f),
                    alpha,
                )
                val bottomColor = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(opaqueBase, Color.BLACK, 0.10f),
                    alpha,
                )
                drawable.orientation = GradientDrawable.Orientation.TOP_BOTTOM
                drawable.colors = intArrayOf(topColor, bottomColor)
                drawable.setStroke(
                    max(1, (1.4f * density).roundToInt()),
                    ColorUtils.setAlphaComponent(
                        ColorUtils.blendARGB(opaqueBase, Color.WHITE, 0.35f),
                        150,
                    ),
                )
            }

            "neon_border" -> {
                drawable.colors = null
                drawable.setColor(ColorUtils.setAlphaComponent(opaqueBase, alpha))
                drawable.setStroke(
                    max(1, (1.6f * density).roundToInt()),
                    ColorUtils.setAlphaComponent(accentColor, 195),
                )
            }

            else -> {
                drawable.colors = null
                drawable.setColor(ColorUtils.setAlphaComponent(opaqueBase, alpha))
                drawable.setStroke(0, Color.TRANSPARENT)
            }
        }
    }
}
