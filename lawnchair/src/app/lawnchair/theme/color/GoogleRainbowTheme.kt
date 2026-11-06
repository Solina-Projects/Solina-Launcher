package app.lawnchair.theme.color

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * Google 4-color Rainbow palette & shader helper (#4285F4 Blue, #EA4335 Red, #FBBC05 Yellow, #34A853 Green).
 * Used across app/folder/dock/suggestion labels, search bar icons, backgrounds, and outlines.
 */
object GoogleRainbowTheme {

    const val GOOGLE_BLUE: Int = 0xFF4285F4.toInt()
    const val GOOGLE_RED: Int = 0xFFEA4335.toInt()
    const val GOOGLE_YELLOW: Int = 0xFFFBBC05.toInt()
    const val GOOGLE_GREEN: Int = 0xFF34A853.toInt()

    /**
     * Sentinel ARGB value returned by [ColorOption.GoogleRainbow.colorPreferenceEntry]
     * so legacy integer-based callers still receive a valid visible color while
     * rainbow-aware views/composables can detect it with [isGoogleRainbowColor].
     */
    const val GOOGLE_RAINBOW_SENTINEL: Int = 0xFE4285F4.toInt()

    @JvmField
    val GOOGLE_COLORS: IntArray = intArrayOf(
        GOOGLE_BLUE,
        GOOGLE_RED,
        GOOGLE_YELLOW,
        GOOGLE_GREEN,
    )

    @JvmField
    val GOOGLE_COLOR_POSITIONS: FloatArray = floatArrayOf(
        0.0f,
        0.34f,
        0.67f,
        1.0f,
    )

    @JvmStatic
    fun isGoogleRainbowColor(color: Int?): Boolean {
        return color != null && color == GOOGLE_RAINBOW_SENTINEL
    }

    @JvmStatic
    fun isGoogleRainbowOption(option: ColorOption?): Boolean {
        return option is ColorOption.GoogleRainbow
    }

    @JvmStatic
    fun getGoogleColorForIndex(index: Int): Int {
        val safeIdx = ((index % GOOGLE_COLORS.size) + GOOGLE_COLORS.size) % GOOGLE_COLORS.size
        return GOOGLE_COLORS[safeIdx]
    }

    @JvmStatic
    @JvmOverloads
    fun createHorizontalShader(widthPx: Float, alpha: Int = 255): LinearGradient {
        val safeWidth = widthPx.coerceAtLeast(24f)
        val clampedAlpha = alpha.coerceIn(0, 255)
        val colors = if (clampedAlpha >= 255) {
            GOOGLE_COLORS
        } else {
            IntArray(GOOGLE_COLORS.size) { i ->
                ColorUtils.setAlphaComponent(GOOGLE_COLORS[i], clampedAlpha)
            }
        }
        return LinearGradient(
            0f,
            0f,
            safeWidth,
            0f,
            colors,
            GOOGLE_COLOR_POSITIONS,
            Shader.TileMode.CLAMP,
        )
    }

    @JvmStatic
    fun createSpanShader(startX: Float, endX: Float, alpha: Int = 255): LinearGradient {
        val safeEnd = if (endX - startX < 16f) startX + 48f else endX
        val clampedAlpha = alpha.coerceIn(0, 255)
        val colors = if (clampedAlpha >= 255) {
            GOOGLE_COLORS
        } else {
            IntArray(GOOGLE_COLORS.size) { i ->
                ColorUtils.setAlphaComponent(GOOGLE_COLORS[i], clampedAlpha)
            }
        }
        return LinearGradient(
            startX,
            0f,
            safeEnd,
            0f,
            colors,
            GOOGLE_COLOR_POSITIONS,
            Shader.TileMode.CLAMP,
        )
    }

    /**
     * Applies or clears a horizontal Google 4-color rainbow [LinearGradient] on a [TextView]
     * aligned tightly to the rendered text span so even short labels show all 4 Google colors.
     */
    @JvmStatic
    @JvmOverloads
    fun applyTextViewRainbow(textView: TextView, enabled: Boolean, alphaFraction: Float = 1f) {
        if (!enabled) {
            if (textView.paint.shader != null) {
                textView.paint.shader = null
                textView.invalidate()
            }
            return
        }
        val text = textView.text?.toString().orEmpty()
        val viewWidth = textView.width.toFloat()
        val textWidth = if (text.isNotEmpty()) {
            textView.paint.measureText(text).coerceAtLeast(24f)
        } else {
            viewWidth.coerceAtLeast(48f)
        }
        val contentLeft = textView.compoundPaddingLeft.toFloat()
        val contentRight = (viewWidth - textView.compoundPaddingRight).coerceAtLeast(contentLeft + textWidth)
        val availableWidth = (contentRight - contentLeft).coerceAtLeast(textWidth)
        val startX = if (viewWidth > 0f) {
            contentLeft + ((availableWidth - textWidth) / 2f).coerceAtLeast(0f)
        } else {
            0f
        }
        val endX = startX + textWidth
        val alphaInt = (alphaFraction.coerceIn(0f, 1f) * 255f).toInt()
        textView.paint.shader = createSpanShader(startX, endX, alphaInt)
    }

    @JvmStatic
    @JvmOverloads
    fun createRainbowGradientDrawable(
        cornerRadiusPx: Float,
        alpha: Int = 255,
        strokeWidthPx: Int = 0,
    ): GradientDrawable {
        val clampedAlpha = alpha.coerceIn(0, 255)
        val fillColors = IntArray(GOOGLE_COLORS.size) { i ->
            ColorUtils.setAlphaComponent(GOOGLE_COLORS[i], clampedAlpha)
        }
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            fillColors,
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            if (strokeWidthPx > 0) {
                setStroke(
                    strokeWidthPx,
                    ColorUtils.setAlphaComponent(Color.WHITE, (clampedAlpha * 0.7f).toInt().coerceIn(60, 220)),
                )
            }
        }
    }
}
