package app.lawnchair.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import androidx.core.graphics.ColorUtils
import app.lawnchair.theme.color.ColorOption
import app.lawnchair.theme.color.GoogleRainbowTheme
import app.lawnchair.theme.color.tokens.ColorTokens
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders decorative multi-pattern outlines for the Suggestions Strip and All Apps drawer
 * (both container-level and per-app-cell level).
 */
object OutlineStyleRenderer {

    const val STYLE_SOLID = "solid"
    const val STYLE_DOUBLE_LINE = "double_line"
    const val STYLE_DASHED = "dashed"
    const val STYLE_DOTTED = "dotted"
    const val STYLE_GOOGLE_RAINBOW = "google_rainbow"
    const val STYLE_NEON_GLOW = "neon_glow"
    const val STYLE_DIAMOND_CORNERS = "diamond_corners"
    const val STYLE_GRADIENT_FADE = "gradient_fade"

    @JvmStatic
    fun resolveOutlineColor(context: Context, colorOption: ColorOption?): Int {
        if (colorOption == null || colorOption is ColorOption.Default) {
            return ColorTokens.ColorAccent.resolveColor(context)
        }
        if (colorOption is ColorOption.GoogleRainbow) {
            return GoogleRainbowTheme.GOOGLE_RAINBOW_SENTINEL
        }
        val resolved = colorOption.colorPreferenceEntry.lightColor.invoke(context)
        return if (resolved != 0) resolved else ColorTokens.ColorAccent.resolveColor(context)
    }

    @JvmStatic
    @JvmOverloads
    fun drawOutline(
        canvas: Canvas,
        bounds: RectF,
        cornerRadiusPx: Float,
        style: String,
        strokeWidthDp: Float,
        color: Int,
        density: Float,
        alphaMultiplier: Float = 1f,
    ) {
        if (bounds.width() <= 2f || bounds.height() <= 2f || strokeWidthDp <= 0f) return

        val strokePx = max(1f, strokeWidthDp * density)
        val halfStroke = strokePx * 0.5f
        val rect = RectF(
            bounds.left + halfStroke,
            bounds.top + halfStroke,
            bounds.right - halfStroke,
            bounds.bottom - halfStroke,
        )
        if (rect.width() <= 1f || rect.height() <= 1f) return

        val isRainbow = style == STYLE_GOOGLE_RAINBOW || GoogleRainbowTheme.isGoogleRainbowColor(color)
        val baseAlpha = (235f * alphaMultiplier.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        val safeBaseColor = if (GoogleRainbowTheme.isGoogleRainbowColor(color)) {
            GoogleRainbowTheme.GOOGLE_BLUE
        } else {
            color
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            this.strokeWidth = strokePx
            this.strokeCap = Paint.Cap.ROUND
            this.strokeJoin = Paint.Join.ROUND
        }

        if (isRainbow) {
            paint.shader = LinearGradient(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                IntArray(GoogleRainbowTheme.GOOGLE_COLORS.size) { i ->
                    ColorUtils.setAlphaComponent(GoogleRainbowTheme.GOOGLE_COLORS[i], baseAlpha)
                },
                GoogleRainbowTheme.GOOGLE_COLOR_POSITIONS,
                Shader.TileMode.CLAMP,
            )
        } else {
            paint.color = ColorUtils.setAlphaComponent(safeBaseColor, baseAlpha)
        }

        when (style) {
            STYLE_DOUBLE_LINE -> {
                val outerWidth = max(1f, strokePx * 0.65f)
                paint.strokeWidth = outerWidth
                canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)

                val gap = max(2.5f * density, strokePx * 1.6f)
                val innerRect = RectF(
                    rect.left + gap,
                    rect.top + gap,
                    rect.right - gap,
                    rect.bottom - gap,
                )
                if (innerRect.width() > 2f && innerRect.height() > 2f) {
                    val innerRadius = max(2f, cornerRadiusPx - gap)
                    if (!isRainbow) {
                        paint.color = ColorUtils.setAlphaComponent(
                            safeBaseColor,
                            (baseAlpha * 0.65f).roundToInt(),
                        )
                    }
                    canvas.drawRoundRect(innerRect, innerRadius, innerRadius, paint)
                }
            }

            STYLE_DASHED -> {
                val dashLen = max(6f * density, strokePx * 3.2f)
                val dashGap = max(4f * density, strokePx * 2.0f)
                paint.pathEffect = DashPathEffect(floatArrayOf(dashLen, dashGap), 0f)
                canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
            }

            STYLE_DOTTED -> {
                val dotGap = max(4.5f * density, strokePx * 2.4f)
                paint.pathEffect = DashPathEffect(floatArrayOf(0.5f, dotGap), 0f)
                canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
            }

            STYLE_NEON_GLOW -> {
                val glowPaint = Paint(paint).apply {
                    this.strokeWidth = strokePx * 2.8f
                    if (!isRainbow) {
                        this.color = ColorUtils.setAlphaComponent(
                            safeBaseColor,
                            (baseAlpha * 0.28f).roundToInt(),
                        )
                    } else {
                        this.alpha = (baseAlpha * 0.32f).roundToInt()
                    }
                }
                canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, glowPaint)

                val midGlowPaint = Paint(paint).apply {
                    this.strokeWidth = strokePx * 1.6f
                    if (!isRainbow) {
                        this.color = ColorUtils.setAlphaComponent(
                            ColorUtils.blendARGB(safeBaseColor, Color.WHITE, 0.25f),
                            (baseAlpha * 0.55f).roundToInt(),
                        )
                    }
                }
                canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, midGlowPaint)

                paint.strokeWidth = max(1f, strokePx * 0.85f)
                if (!isRainbow) {
                    paint.color = ColorUtils.setAlphaComponent(
                        ColorUtils.blendARGB(safeBaseColor, Color.WHITE, 0.45f),
                        baseAlpha,
                    )
                }
                canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
            }

            STYLE_DIAMOND_CORNERS -> {
                canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
                val diamondRadius = max(3f * density, strokePx * 1.8f)
                val diamondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.FILL
                    if (isRainbow) {
                        this.shader = paint.shader
                    } else {
                        this.color = ColorUtils.setAlphaComponent(
                            ColorUtils.blendARGB(safeBaseColor, Color.WHITE, 0.2f),
                            baseAlpha,
                        )
                    }
                }
                drawDiamond(canvas, rect.centerX(), rect.top, diamondRadius, diamondPaint)
                drawDiamond(canvas, rect.centerX(), rect.bottom, diamondRadius, diamondPaint)
                drawDiamond(canvas, rect.left, rect.centerY(), diamondRadius, diamondPaint)
                drawDiamond(canvas, rect.right, rect.centerY(), diamondRadius, diamondPaint)
            }

            STYLE_GRADIENT_FADE -> {
                val topBright = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(safeBaseColor, Color.WHITE, 0.35f),
                    baseAlpha,
                )
                val midColor = ColorUtils.setAlphaComponent(
                    safeBaseColor,
                    (baseAlpha * 0.45f).roundToInt(),
                )
                val bottomRim = ColorUtils.setAlphaComponent(
                    safeBaseColor,
                    (baseAlpha * 0.80f).roundToInt(),
                )
                paint.shader = LinearGradient(
                    rect.left,
                    rect.top,
                    rect.left,
                    rect.bottom,
                    intArrayOf(topBright, midColor, bottomRim),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
            }

            else -> {
                // STYLE_SOLID or STYLE_GOOGLE_RAINBOW
                canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
            }
        }
    }

    private fun drawDiamond(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        paint: Paint,
    ) {
        val path = Path().apply {
            moveTo(cx, cy - r)
            lineTo(cx + r, cy)
            lineTo(cx, cy + r)
            lineTo(cx - r, cy)
            close()
        }
        canvas.drawPath(path, paint)
    }

    @JvmStatic
    @JvmOverloads
    fun createOutlineDrawable(
        cornerRadiusPx: Float,
        style: String,
        strokeWidthDp: Float,
        color: Int,
        density: Float,
        insetPx: Int = 0,
        baseDrawable: Drawable? = null,
    ): Drawable {
        val outlineDrawable = object : Drawable() {
            private val rectF = RectF()
            private var currentAlpha = 255

            override fun draw(canvas: Canvas) {
                val b: Rect = bounds
                rectF.set(
                    (b.left + insetPx).toFloat(),
                    (b.top + insetPx / 2).toFloat(),
                    (b.right - insetPx).toFloat(),
                    (b.bottom - insetPx / 2).toFloat(),
                )
                drawOutline(
                    canvas = canvas,
                    bounds = rectF,
                    cornerRadiusPx = cornerRadiusPx,
                    style = style,
                    strokeWidthDp = strokeWidthDp,
                    color = color,
                    density = density,
                    alphaMultiplier = currentAlpha / 255f,
                )
            }

            override fun setAlpha(alpha: Int) {
                currentAlpha = alpha.coerceIn(0, 255)
                invalidateSelf()
            }

            override fun setColorFilter(colorFilter: ColorFilter?) {}

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
        return if (baseDrawable != null) {
            LayerDrawable(arrayOf(baseDrawable, outlineDrawable))
        } else {
            outlineDrawable
        }
    }
}
