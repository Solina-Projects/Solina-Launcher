package app.lawnchair.allapps.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import androidx.core.graphics.ColorUtils
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.theme.color.tokens.ColorTokens
import com.android.launcher3.R
import com.android.systemui.shared.system.BlurUtils
import com.android.systemui.util.dpToPx
import kotlin.math.roundToInt

class SearchItemBackground(
    private val context: Context,
    private val showBackground: Boolean,
    private val roundTop: Boolean,
    private val roundBottom: Boolean,
) {
    private val resources = context.resources
    private val prefs = PreferenceManager.getInstance(context)
    private val searchDecorationPadding = resources.getDimensionPixelSize(R.dimen.search_decoration_padding)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f.dpToPx(resources)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmpPath = Path()
    private val shadowPath = Path()
    private val tmpRect = RectF()
    private val shadowRect = RectF()

    val supportBlur = BlurUtils.supportsBlursOnWindows()

    val focusHighlight = if (supportBlur) {
        ColorTokens.FocusHighlightBlur.resolveColor(context)
    } else {
        ColorTokens.FocusHighlight.resolveColor(context)
    }

    val groupHighlight = if (showBackground) {
        if (supportBlur) {
            ColorTokens.GroupHighlightBlur.resolveColor(context)
        } else {
            ColorTokens.GroupHighlight.resolveColor(context)
        }
    } else {
        0
    }

    private val accentColor = ColorTokens.ColorAccent.resolveColor(context)

    private fun computeCornerRadii(): FloatArray {
        val customGroupRadius = prefs.searchCardCornerRadius.get().toFloat().dpToPx(resources)
        val searchResultRadius = resources.getDimensionPixelSize(R.dimen.search_result_radius).toFloat()
        val topRadius = if (roundTop) customGroupRadius else searchResultRadius
        val bottomRadius = if (roundBottom) customGroupRadius else searchResultRadius
        return floatArrayOf(
            topRadius,
            topRadius,
            topRadius,
            topRadius,
            bottomRadius,
            bottomRadius,
            bottomRadius,
            bottomRadius,
        )
    }

    fun draw(c: Canvas, child: View, isFocused: Boolean) {
        val baseColor = if (isFocused) focusHighlight else groupHighlight
        if (baseColor == 0) return

        val style = prefs.searchCardStyle.get()
        val alphaPercent = prefs.searchCardAlpha.get().coerceIn(20, 100)
        val targetAlpha = ((Color.alpha(baseColor) * alphaPercent) / 100).coerceIn(25, 255)

        var left = child.left.toFloat() + searchDecorationPadding
        var top = child.top.toFloat() + searchDecorationPadding
        var right = child.right.toFloat() - searchDecorationPadding
        var bottom = child.bottom.toFloat() - searchDecorationPadding

        if (child is SearchResultIcon) {
            val iconSize = child.iconSize.toFloat()
            val desiredWidth = iconSize + 48.dpToPx(resources)
            val cellWidth = child.width.toFloat()
            if (desiredWidth < cellWidth) {
                val inset = (cellWidth - desiredWidth) / 2
                left += inset
                right -= inset
            }
            val isTwoLine = child.lineCount > 1
            val insetTop = 6.dpToPx(resources)
            val insetBottom = if (isTwoLine) 6.dpToPx(resources) else 0.dpToPx(resources)
            top += insetTop
            bottom -= insetBottom
        }

        val radii = computeCornerRadii()
        tmpRect.set(left, top, right, bottom)
        tmpPath.reset()
        tmpPath.addRoundRect(tmpRect, radii, Path.Direction.CW)

        paint.shader = null
        when (style) {
            "glass_card" -> {
                val topColor = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(baseColor, Color.WHITE, 0.16f),
                    targetAlpha,
                )
                val bottomColor = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(baseColor, Color.BLACK, 0.10f),
                    targetAlpha,
                )
                paint.shader = LinearGradient(
                    left,
                    top,
                    left,
                    bottom,
                    topColor,
                    bottomColor,
                    Shader.TileMode.CLAMP,
                )
                c.drawPath(tmpPath, paint)
                if (roundTop || roundBottom) {
                    strokePaint.color = ColorUtils.setAlphaComponent(
                        ColorUtils.blendARGB(baseColor, Color.WHITE, 0.38f),
                        (targetAlpha * 0.72f).roundToInt().coerceIn(30, 200),
                    )
                    c.drawPath(tmpPath, strokePaint)
                }
            }

            "tonal_pod" -> {
                val tonalFill = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(baseColor, accentColor, if (isFocused) 0.28f else 0.16f),
                    targetAlpha,
                )
                paint.color = tonalFill
                c.drawPath(tmpPath, paint)
                if (roundTop || roundBottom) {
                    strokePaint.color = ColorUtils.setAlphaComponent(
                        accentColor,
                        (targetAlpha * 0.55f).roundToInt().coerceIn(24, 180),
                    )
                    c.drawPath(tmpPath, strokePaint)
                }
            }

            "elevated_3d" -> {
                if (roundBottom) {
                    val depthPx = 3f.dpToPx(resources)
                    shadowRect.set(left, top + depthPx, right, bottom + depthPx)
                    shadowPath.reset()
                    shadowPath.addRoundRect(shadowRect, radii, Path.Direction.CW)
                    shadowPaint.color = ColorUtils.setAlphaComponent(
                        ColorUtils.blendARGB(baseColor, Color.BLACK, 0.42f),
                        (targetAlpha * 0.85f).roundToInt().coerceIn(40, 230),
                    )
                    c.drawPath(shadowPath, shadowPaint)
                }
                paint.color = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(baseColor, Color.WHITE, 0.10f),
                    targetAlpha,
                )
                c.drawPath(tmpPath, paint)
            }

            else -> {
                paint.color = ColorUtils.setAlphaComponent(baseColor, targetAlpha)
                c.drawPath(tmpPath, paint)
            }
        }
    }
}
