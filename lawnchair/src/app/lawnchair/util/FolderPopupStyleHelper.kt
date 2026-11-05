package app.lawnchair.util

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
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
    fun resolvePopupOutlineRadius(
        context: Context,
        defaultRadius: Float,
    ): Float {
        val prefs = PreferenceManager.getInstance(context)
        val style = prefs.popupSurfaceStyle.get()
        if (style == "classic") {
            return defaultRadius
        }
        val density = context.resources.displayMetrics.density
        return prefs.popupCornerRadiusDp.get().coerceIn(12, 36) * density
    }

    @JvmStatic
    fun applyPopupItemDrawable(
        context: Context,
        drawable: GradientDrawable,
        baseColor: Int,
    ) {
        val prefs = PreferenceManager.getInstance(context)
        val style = prefs.popupSurfaceStyle.get()
        val opacityPct = prefs.popupBgOpacity.get().coerceIn(50, 100)
        val alpha = (opacityPct / 100f * 255f).roundToInt().coerceIn(100, 255)
        val density = context.resources.displayMetrics.density
        val accentColor = ColorTokens.ColorAccent.resolveColor(context)

        drawable.alpha = alpha
        when (style) {
            "glass_card" -> {
                drawable.setStroke(
                    max(1, (1.1f * density).roundToInt()),
                    ColorUtils.setAlphaComponent(Color.WHITE, 95),
                )
            }

            "elevated_3d" -> {
                drawable.setStroke(
                    max(1, (1.4f * density).roundToInt()),
                    ColorUtils.setAlphaComponent(
                        ColorUtils.blendARGB(baseColor, Color.WHITE, 0.35f),
                        150,
                    ),
                )
            }

            "neon_border" -> {
                drawable.setStroke(
                    max(1, (1.6f * density).roundToInt()),
                    ColorUtils.setAlphaComponent(accentColor, 195),
                )
            }

            else -> {
                drawable.setStroke(0, Color.TRANSPARENT)
            }
        }
    }
}
