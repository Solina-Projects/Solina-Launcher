package app.lawnchair.glass

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.theme.color.tokens.ColorTokens
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class LiquidGlassConfig(
    val enabled: Boolean,
    val preset: String,
    val tintSwatch: String,
    val density: Float,
    val blurPx: Int,
    val rimLight: Float,
    val bevel: Float,
    val refraction: Float,
    val dispersion: Float,
    val rimWidthDp: Int,
    val shadow: Float,
)

class LiquidGlassDrawable(
    private val config: LiquidGlassConfig,
    private val baseTintColor: Int,
    private val accentColor: Int,
    private val isDarkTheme: Boolean,
    private val densityScale: Float,
    cornerRadiusPx: Float = 24f * densityScale,
    customCornerRadii: FloatArray? = null,
    private val alphaMultiplier: Float = 1f,
) : Drawable() {

    private var overallAlpha: Int = 255
    private var radii: FloatArray = customCornerRadii?.clone() ?: FloatArray(8) { cornerRadiusPx }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val refractionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val dispersionCyanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val dispersionWarmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rimCatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val outerRect = RectF()
    private val shadowRect = RectF()
    private val insetRect = RectF()
    private val dispersionRectA = RectF()
    private val dispersionRectB = RectF()
    private val bandRect = RectF()

    private val clipPath = Path()
    private val shadowPath = Path()
    private val strokePath = Path()
    private val dispersionPathA = Path()
    private val dispersionPathB = Path()

    fun setCornerRadius(radiusPx: Float) {
        radii = FloatArray(8) { radiusPx }
        invalidateSelf()
    }

    fun setCornerRadii(newRadii: FloatArray) {
        if (newRadii.size == 8) {
            radii = newRadii.clone()
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        outerRect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        LiquidGlassStyleHelper.drawGlassSurface(
            canvas = canvas,
            rect = outerRect,
            radii = radii,
            config = config,
            baseTintColor = baseTintColor,
            accentColor = accentColor,
            isDarkTheme = isDarkTheme,
            densityScale = densityScale,
            alphaMultiplier = (overallAlpha / 255f) * alphaMultiplier,
            bodyPaint = bodyPaint,
            shadowPaint = shadowPaint,
            refractionPaint = refractionPaint,
            dispersionCyanPaint = dispersionCyanPaint,
            dispersionWarmPaint = dispersionWarmPaint,
            bevelPaint = bevelPaint,
            rimCatchPaint = rimCatchPaint,
            hairlinePaint = hairlinePaint,
            shadowRect = shadowRect,
            insetRect = insetRect,
            dispersionRectA = dispersionRectA,
            dispersionRectB = dispersionRectB,
            bandRect = bandRect,
            clipPath = clipPath,
            shadowPath = shadowPath,
            strokePath = strokePath,
            dispersionPathA = dispersionPathA,
            dispersionPathB = dispersionPathB,
        )
    }

    override fun setAlpha(alpha: Int) {
        overallAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = overallAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bodyPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

object LiquidGlassStyleHelper {

    const val STYLE_LIQUID_GLASS = "liquid_glass"

    @JvmStatic
    fun getConfig(context: Context): LiquidGlassConfig {
        val prefs = PreferenceManager.getInstance(context)
        return LiquidGlassConfig(
            enabled = prefs.liquidGlassEnabled.get(),
            preset = prefs.liquidGlassPreset.get(),
            tintSwatch = prefs.liquidGlassTintSwatch.get(),
            density = prefs.liquidGlassDensity.get().coerceIn(0.02f, 1.0f),
            blurPx = prefs.liquidGlassBlurPx.get().coerceIn(0, 40),
            rimLight = prefs.liquidGlassRimLight.get().coerceIn(0f, 2.0f),
            bevel = prefs.liquidGlassBevel.get().coerceIn(0f, 2.5f),
            refraction = prefs.liquidGlassRefraction.get().coerceIn(0f, 0.40f),
            dispersion = prefs.liquidGlassDispersion.get().coerceIn(0f, 0.40f),
            rimWidthDp = prefs.liquidGlassRimWidthDp.get().coerceIn(8, 64),
            shadow = prefs.liquidGlassShadow.get().coerceIn(0f, 2.0f),
        )
    }

    @JvmStatic
    fun shouldUseLiquidGlass(
        context: Context,
        localStyleValue: String?,
        surfaceToggleValue: Boolean,
    ): Boolean {
        if (localStyleValue == STYLE_LIQUID_GLASS) return true
        val prefs = PreferenceManager.getInstance(context)
        return prefs.liquidGlassEnabled.get() && surfaceToggleValue
    }

    @JvmStatic
    fun shouldUseForDock(context: Context, localPreset: String?): Boolean {
        val prefs = PreferenceManager.getInstance(context)
        return shouldUseLiquidGlass(context, localPreset, prefs.liquidGlassApplyDock.get())
    }

    @JvmStatic
    fun shouldUseForQsb(context: Context, localStyle: String?): Boolean {
        val prefs = PreferenceManager.getInstance(context)
        return shouldUseLiquidGlass(context, localStyle, prefs.liquidGlassApplyQsb.get())
    }

    @JvmStatic
    fun shouldUseForSmartspace(context: Context, localStyle: String?): Boolean {
        val prefs = PreferenceManager.getInstance(context)
        return shouldUseLiquidGlass(context, localStyle, prefs.liquidGlassApplySmartspace.get())
    }

    @JvmStatic
    fun shouldUseForDrawer(context: Context, localStyle: String?): Boolean {
        val prefs = PreferenceManager.getInstance(context)
        return shouldUseLiquidGlass(context, localStyle, prefs.liquidGlassApplyDrawer.get())
    }

    @JvmStatic
    @JvmOverloads
    fun shouldUseForSuggestions(context: Context, localStyle: String? = null): Boolean {
        val prefs = PreferenceManager.getInstance(context)
        return shouldUseLiquidGlass(context, localStyle, prefs.liquidGlassApplySuggestions.get())
    }

    @JvmStatic
    fun shouldUseForSearchCards(context: Context, localStyle: String?): Boolean {
        val prefs = PreferenceManager.getInstance(context)
        return shouldUseLiquidGlass(context, localStyle, prefs.liquidGlassApplySearchCards.get())
    }

    @JvmStatic
    fun shouldUseForFoldersPopups(context: Context, localStyle: String?): Boolean {
        val prefs = PreferenceManager.getInstance(context)
        return shouldUseLiquidGlass(context, localStyle, prefs.liquidGlassApplyFoldersPopups.get())
    }

    @JvmStatic
    fun shouldUseForRecents(context: Context, localStyle: String?): Boolean {
        val prefs = PreferenceManager.getInstance(context)
        return shouldUseLiquidGlass(context, localStyle, prefs.liquidGlassApplyRecents.get())
    }

    @JvmStatic
    fun isDarkConfiguration(context: Context): Boolean {
        val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMask == Configuration.UI_MODE_NIGHT_YES
    }

    @JvmStatic
    fun resolveTintColor(
        swatch: String,
        isDark: Boolean,
        fallbackColor: Int,
        accentColor: Int,
    ): Int {
        return when (swatch) {
            "accent" -> ColorUtils.blendARGB(
                if (isDark) Color.rgb(30, 32, 40) else Color.WHITE,
                accentColor,
                if (isDark) 0.42f else 0.28f,
            )

            "clear_white" -> Color.rgb(255, 255, 255)

            "warm_sand" -> if (isDark) Color.rgb(42, 34, 28) else Color.rgb(255, 246, 235)

            "sky_ice" -> if (isDark) Color.rgb(22, 34, 46) else Color.rgb(235, 244, 255)

            "rose_quartz" -> if (isDark) Color.rgb(44, 24, 34) else Color.rgb(255, 235, 240)

            "mint_jade" -> if (isDark) Color.rgb(20, 38, 30) else Color.rgb(234, 250, 242)

            "lilac_mist" -> if (isDark) Color.rgb(34, 26, 46) else Color.rgb(243, 238, 255)

            "obsidian_night" -> Color.rgb(20, 24, 36)

            "ocean_deep" -> Color.rgb(18, 34, 42)

            else -> {
                // "auto_theme"
                if (fallbackColor != 0 && Color.alpha(fallbackColor) > 0) {
                    ColorUtils.blendARGB(
                        fallbackColor,
                        if (isDark) Color.rgb(30, 30, 34) else Color.WHITE,
                        0.45f,
                    )
                } else if (isDark) {
                    Color.rgb(30, 30, 34)
                } else {
                    Color.WHITE
                }
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun createDrawable(
        context: Context,
        cornerRadiusPx: Float,
        fallbackColor: Int = 0,
        alphaMultiplier: Float = 1f,
        customCornerRadii: FloatArray? = null,
    ): LiquidGlassDrawable {
        val config = getConfig(context)
        val isDark = isDarkConfiguration(context)
        val accentColor = ColorTokens.ColorAccent.resolveColor(context)
        val baseTint = resolveTintColor(config.tintSwatch, isDark, fallbackColor, accentColor)
        val densityScale = context.resources.displayMetrics.density
        return LiquidGlassDrawable(
            config = config,
            baseTintColor = baseTint,
            accentColor = accentColor,
            isDarkTheme = isDark,
            densityScale = densityScale,
            cornerRadiusPx = cornerRadiusPx,
            customCornerRadii = customCornerRadii,
            alphaMultiplier = alphaMultiplier,
        )
    }

    @JvmStatic
    @JvmOverloads
    fun drawOnCanvas(
        context: Context,
        canvas: Canvas,
        rect: RectF,
        radii: FloatArray,
        fallbackColor: Int = 0,
        alphaMultiplier: Float = 1f,
    ) {
        val drawable = createDrawable(
            context = context,
            cornerRadiusPx = radii.firstOrNull() ?: 24f,
            fallbackColor = fallbackColor,
            alphaMultiplier = alphaMultiplier,
            customCornerRadii = radii,
        )
        drawable.setBounds(
            rect.left.roundToInt(),
            rect.top.roundToInt(),
            rect.right.roundToInt(),
            rect.bottom.roundToInt(),
        )
        drawable.draw(canvas)
    }

    @JvmStatic
    @JvmOverloads
    fun drawOnCanvasBounds(
        context: Context,
        canvas: Canvas,
        bounds: Rect,
        cornerRadiusPx: Float,
        fallbackColor: Int = 0,
        alphaMultiplier: Float = 1f,
    ) {
        if (bounds.isEmpty) return
        val drawable = createDrawable(
            context = context,
            cornerRadiusPx = cornerRadiusPx,
            fallbackColor = fallbackColor,
            alphaMultiplier = alphaMultiplier,
        )
        drawable.bounds = bounds
        drawable.draw(canvas)
    }

    internal fun drawGlassSurface(
        canvas: Canvas,
        rect: RectF,
        radii: FloatArray,
        config: LiquidGlassConfig,
        baseTintColor: Int,
        accentColor: Int,
        isDarkTheme: Boolean,
        densityScale: Float,
        alphaMultiplier: Float,
        bodyPaint: Paint,
        shadowPaint: Paint,
        refractionPaint: Paint,
        dispersionCyanPaint: Paint,
        dispersionWarmPaint: Paint,
        bevelPaint: Paint,
        rimCatchPaint: Paint,
        hairlinePaint: Paint,
        shadowRect: RectF,
        insetRect: RectF,
        dispersionRectA: RectF,
        dispersionRectB: RectF,
        bandRect: RectF,
        clipPath: Path,
        shadowPath: Path,
        strokePath: Path,
        dispersionPathA: Path,
        dispersionPathB: Path,
    ) {
        val width = rect.width()
        val height = rect.height()
        if (width <= 1f || height <= 1f) return

        val effectiveAlpha = alphaMultiplier.coerceIn(0f, 1.25f)

        // 1. Soft Ambient Elevation Shadow (--m-shadow)
        if (config.shadow > 0.02f) {
            val shadowOffsetPx = (2.5f * densityScale * min(1.5f, config.shadow)).coerceAtMost(height * 0.12f)
            val shadowAlpha = ((if (isDarkTheme) 56 else 28) * config.shadow * effectiveAlpha)
                .roundToInt()
                .coerceIn(0, 140)
            if (shadowAlpha > 0) {
                shadowRect.set(
                    rect.left + 0.5f * densityScale,
                    rect.top + shadowOffsetPx,
                    rect.right - 0.5f * densityScale,
                    rect.bottom + shadowOffsetPx * 0.6f,
                )
                shadowPath.reset()
                shadowPath.addRoundRect(shadowRect, radii, Path.Direction.CW)
                shadowPaint.shader = null
                shadowPaint.color = Color.argb(shadowAlpha, 0, 0, 0)
                canvas.drawPath(shadowPath, shadowPaint)
            }
        }

        clipPath.reset()
        clipPath.addRoundRect(rect, radii, Path.Direction.CW)

        val saveCount = canvas.save()
        canvas.clipPath(clipPath)

        // 2. Adaptive Glass Body Fill (--m-tint + thin..dense interpolation + frost lift)
        // Follows glasswork playground.js:
        // Light thin=0.06..dense=0.22; Dark thin=0.16..dense=0.38; scaled for Android readability
        val thinAlpha = if (isDarkTheme) 0.24f else 0.16f
        val denseAlpha = if (isDarkTheme) 0.86f else 0.78f
        val frostBoost = (config.blurPx / 40f) * 0.16f
        val rawBodyOpacity = (thinAlpha + (denseAlpha - thinAlpha) * config.density + frostBoost)
            .coerceIn(0.08f, 0.94f)
        val bodyAlpha = (rawBodyOpacity * 255f * effectiveAlpha).roundToInt().coerceIn(18, 245)

        val frostTint = if (config.blurPx > 0) {
            ColorUtils.blendARGB(
                baseTintColor,
                if (isDarkTheme) Color.rgb(65, 72, 90) else Color.WHITE,
                (config.blurPx / 40f) * 0.24f,
            )
        } else {
            baseTintColor
        }

        val topBodyColor = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(frostTint, Color.WHITE, if (isDarkTheme) 0.14f else 0.26f),
            min(255, (bodyAlpha * 1.08f).roundToInt()),
        )
        val midBodyColor = ColorUtils.setAlphaComponent(frostTint, bodyAlpha)
        val bottomBodyColor = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(
                frostTint,
                if (isDarkTheme) Color.BLACK else accentColor,
                if (isDarkTheme) 0.18f else 0.08f,
            ),
            max(14, (bodyAlpha * 0.92f).roundToInt()),
        )

        bodyPaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.left + width * 0.35f,
            rect.bottom,
            intArrayOf(topBodyColor, midBodyColor, bottomBodyColor),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(rect, bodyPaint)

        // 3. Inner Slab Bevel (--m-bevel from glass.css)
        // Soft inner light & shadow bands just inside the edges so the surface reads as a thick glass slab
        if (config.bevel > 0.02f) {
            val bevelBand = min(min(width, height) * 0.28f, (8f + config.rimWidthDp * 0.22f) * densityScale)
            val topBevelAlpha = ((if (isDarkTheme) 62 else 115) * config.bevel * effectiveAlpha)
                .roundToInt()
                .coerceIn(0, 220)
            val sideBevelAlpha = ((if (isDarkTheme) 36 else 72) * config.bevel * effectiveAlpha)
                .roundToInt()
                .coerceIn(0, 180)
            val bottomBevelAlpha = ((if (isDarkTheme) 48 else 26) * config.bevel * effectiveAlpha)
                .roundToInt()
                .coerceIn(0, 165)

            // Top inner bevel highlight (inset 0 6px 8px -6px)
            if (topBevelAlpha > 0 && bevelBand > 1f) {
                bandRect.set(rect.left, rect.top, rect.right, rect.top + bevelBand)
                bevelPaint.shader = LinearGradient(
                    rect.left,
                    rect.top,
                    rect.left,
                    rect.top + bevelBand,
                    Color.argb(topBevelAlpha, 255, 255, 255),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(bandRect, bevelPaint)
            }

            // Left inner bevel highlight (inset 6px 0 8px -6px)
            if (sideBevelAlpha > 0 && bevelBand > 1f) {
                bandRect.set(rect.left, rect.top, rect.left + bevelBand, rect.bottom)
                bevelPaint.shader = LinearGradient(
                    rect.left,
                    rect.top,
                    rect.left + bevelBand,
                    rect.top,
                    Color.argb(sideBevelAlpha, 255, 255, 255),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(bandRect, bevelPaint)
            }

            // Bottom inner slab depth (inset 0 -6px 8px -6px)
            if (bottomBevelAlpha > 0 && bevelBand > 1f) {
                bandRect.set(rect.left, rect.bottom - bevelBand, rect.right, rect.bottom)
                val bottomSlabColor = if (isDarkTheme) {
                    Color.argb(bottomBevelAlpha, 0, 0, 0)
                } else {
                    Color.argb(bottomBevelAlpha, 255, 255, 255)
                }
                bevelPaint.shader = LinearGradient(
                    rect.left,
                    rect.bottom,
                    rect.left,
                    rect.bottom - bevelBand,
                    bottomSlabColor,
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(bandRect, bevelPaint)
            }
        }

        // 4. Rim Lens Refraction & Chromatic Dispersion (refract.js: rimStrength + RGB split)
        if (config.refraction > 0.01f || config.dispersion > 0.01f) {
            val rimPx = min(
                min(width, height) * 0.24f,
                config.rimWidthDp * densityScale * 0.35f,
            ).coerceAtLeast(1.5f * densityScale)

            // Refraction inner caustic ring
            if (config.refraction > 0.01f) {
                val refractAlpha = ((config.refraction / 0.40f) * 115f * effectiveAlpha)
                    .roundToInt()
                    .coerceIn(0, 175)
                if (refractAlpha > 0) {
                    val halfStroke = rimPx * 0.45f
                    insetRect.set(
                        rect.left + halfStroke,
                        rect.top + halfStroke,
                        rect.right - halfStroke,
                        rect.bottom - halfStroke,
                    )
                    val insetRadii = FloatArray(8) { idx -> max(0f, radii[idx] - halfStroke) }
                    strokePath.reset()
                    strokePath.addRoundRect(insetRect, insetRadii, Path.Direction.CW)

                    refractionPaint.strokeWidth = rimPx * 0.65f
                    refractionPaint.shader = LinearGradient(
                        rect.left,
                        rect.top,
                        rect.right,
                        rect.bottom,
                        intArrayOf(
                            Color.argb(refractAlpha, 255, 255, 255),
                            ColorUtils.setAlphaComponent(accentColor, (refractAlpha * 0.45f).roundToInt()),
                            Color.argb((refractAlpha * 0.75f).roundToInt(), 255, 255, 255),
                        ),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                    canvas.drawPath(strokePath, refractionPaint)
                }
            }

            // Chromatic Dispersion (RGB prismatic edge split: Cyan top-left, Rose/Amber bottom-right)
            if (config.dispersion > 0.01f) {
                val dispNorm = (config.dispersion / 0.40f).coerceIn(0f, 1f)
                val shiftPx = (0.8f + dispNorm * 2.2f) * densityScale
                val dispStroke = (1.1f + dispNorm * 1.4f) * densityScale
                val dispAlpha = (dispNorm * 125f * effectiveAlpha).roundToInt().coerceIn(0, 170)

                if (dispAlpha > 0) {
                    val inset = dispStroke * 0.85f
                    val insetRadii = FloatArray(8) { idx -> max(0f, radii[idx] - inset) }

                    // Cyan / Sky prismatic channel shifted toward light source (top-left)
                    dispersionRectA.set(
                        rect.left + inset - shiftPx * 0.5f,
                        rect.top + inset - shiftPx * 0.5f,
                        rect.right - inset - shiftPx * 0.5f,
                        rect.bottom - inset - shiftPx * 0.5f,
                    )
                    dispersionPathA.reset()
                    dispersionPathA.addRoundRect(dispersionRectA, insetRadii, Path.Direction.CW)
                    dispersionCyanPaint.strokeWidth = dispStroke
                    dispersionCyanPaint.shader = LinearGradient(
                        rect.left,
                        rect.top,
                        rect.right,
                        rect.bottom,
                        intArrayOf(
                            Color.argb(dispAlpha, 56, 189, 248), // Cyan
                            Color.TRANSPARENT,
                            Color.argb((dispAlpha * 0.65f).roundToInt(), 168, 85, 247), // Violet
                        ),
                        floatArrayOf(0f, 0.55f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                    canvas.drawPath(dispersionPathA, dispersionCyanPaint)

                    // Warm Rose / Amber prismatic channel shifted toward opposite rim (bottom-right)
                    dispersionRectB.set(
                        rect.left + inset + shiftPx * 0.5f,
                        rect.top + inset + shiftPx * 0.5f,
                        rect.right - inset + shiftPx * 0.5f,
                        rect.bottom - inset + shiftPx * 0.5f,
                    )
                    dispersionPathB.reset()
                    dispersionPathB.addRoundRect(dispersionRectB, insetRadii, Path.Direction.CW)
                    dispersionWarmPaint.strokeWidth = dispStroke
                    dispersionWarmPaint.shader = LinearGradient(
                        rect.left,
                        rect.top,
                        rect.right,
                        rect.bottom,
                        intArrayOf(
                            Color.argb((dispAlpha * 0.6f).roundToInt(), 251, 191, 36), // Amber
                            Color.TRANSPARENT,
                            Color.argb(dispAlpha, 244, 114, 182), // Rose
                        ),
                        floatArrayOf(0f, 0.45f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                    canvas.drawPath(dispersionPathB, dispersionWarmPaint)
                }
            }
        }

        // 5. Directional Rim Light (--m-catch from glass.css)
        // Top-left bright specular catch (1.0x), bottom-right secondary reflection (0.6x),
        // and crisp 0.5dp continuous hairline outline (0.3x).
        if (config.rimLight > 0.02f) {
            val catchScale = config.rimLight.coerceIn(0f, 2.0f)
            val primaryCatchAlpha = ((if (isDarkTheme) 155 else 210) * min(1.35f, catchScale) * effectiveAlpha)
                .roundToInt()
                .coerceIn(0, 255)
            val secondaryCatchAlpha = (primaryCatchAlpha * 0.60f).roundToInt().coerceIn(0, 220)
            val midCatchAlpha = (primaryCatchAlpha * 0.14f).roundToInt().coerceIn(0, 120)
            val hairlineAlpha = (primaryCatchAlpha * 0.34f).roundToInt().coerceIn(0, 160)

            val catchStrokeWidth = (1.5f * densityScale * (0.75f + 0.25f * catchScale)).coerceAtLeast(1f)
            val halfCatch = catchStrokeWidth * 0.5f
            insetRect.set(
                rect.left + halfCatch,
                rect.top + halfCatch,
                rect.right - halfCatch,
                rect.bottom - halfCatch,
            )
            val catchRadii = FloatArray(8) { idx -> max(0f, radii[idx] - halfCatch) }
            strokePath.reset()
            strokePath.addRoundRect(insetRect, catchRadii, Path.Direction.CW)

            rimCatchPaint.strokeWidth = catchStrokeWidth
            rimCatchPaint.shader = LinearGradient(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                intArrayOf(
                    Color.argb(primaryCatchAlpha, 255, 255, 255),
                    Color.argb(midCatchAlpha, 255, 255, 255),
                    Color.argb(secondaryCatchAlpha, 255, 255, 255),
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(strokePath, rimCatchPaint)

            if (hairlineAlpha > 0) {
                hairlinePaint.strokeWidth = max(1f, 0.6f * densityScale)
                hairlinePaint.shader = null
                hairlinePaint.color = Color.argb(hairlineAlpha, 255, 255, 255)
                canvas.drawPath(strokePath, hairlinePaint)
            }
        }

        canvas.restoreToCount(saveCount)
    }

    /**
     * Applies one of the 5 curated `kubai087/glasswork` presets to [PreferenceManager].
     */
    @JvmStatic
    fun applyPreset(prefs: PreferenceManager, presetId: String) {
        prefs.liquidGlassPreset.set(presetId)
        when (presetId) {
            "clear" -> {
                prefs.liquidGlassDensity.set(0.08f)
                prefs.liquidGlassBlurPx.set(0)
                prefs.liquidGlassRimWidthDp.set(32)
                prefs.liquidGlassRefraction.set(0.22f)
                prefs.liquidGlassDispersion.set(0.24f)
                prefs.liquidGlassRimLight.set(1.35f)
                prefs.liquidGlassBevel.set(1.15f)
                prefs.liquidGlassShadow.set(0.90f)
            }

            "frosted" -> {
                prefs.liquidGlassDensity.set(0.45f)
                prefs.liquidGlassBlurPx.set(28)
                prefs.liquidGlassRimWidthDp.set(20)
                prefs.liquidGlassRefraction.set(0.06f)
                prefs.liquidGlassDispersion.set(0.06f)
                prefs.liquidGlassRimLight.set(0.85f)
                prefs.liquidGlassBevel.set(0.75f)
                prefs.liquidGlassShadow.set(1.00f)
            }

            "thick_slab" -> {
                prefs.liquidGlassDensity.set(0.20f)
                prefs.liquidGlassBlurPx.set(10)
                prefs.liquidGlassRimWidthDp.set(56)
                prefs.liquidGlassRefraction.set(0.34f)
                prefs.liquidGlassDispersion.set(0.32f)
                prefs.liquidGlassRimLight.set(1.60f)
                prefs.liquidGlassBevel.set(2.00f)
                prefs.liquidGlassShadow.set(1.35f)
            }

            "vivid" -> {
                prefs.liquidGlassDensity.set(0.65f)
                prefs.liquidGlassBlurPx.set(18)
                prefs.liquidGlassRimWidthDp.set(36)
                prefs.liquidGlassRefraction.set(0.26f)
                prefs.liquidGlassDispersion.set(0.38f)
                prefs.liquidGlassRimLight.set(1.50f)
                prefs.liquidGlassBevel.set(1.30f)
                prefs.liquidGlassShadow.set(1.15f)
            }

            else -> {
                // "lumno" (Default balanced Liquid Glass from kubai087/glasswork)
                prefs.liquidGlassDensity.set(0.25f)
                prefs.liquidGlassBlurPx.set(14)
                prefs.liquidGlassRimWidthDp.set(28)
                prefs.liquidGlassRefraction.set(0.15f)
                prefs.liquidGlassDispersion.set(0.15f)
                prefs.liquidGlassRimLight.set(1.00f)
                prefs.liquidGlassBevel.set(1.00f)
                prefs.liquidGlassShadow.set(1.00f)
            }
        }
    }

    /**
     * Generates a standards-compliant `kubai087/glasswork` CSS snippet matching the current configuration.
     */
    @JvmStatic
    fun generateGlassworkCss(
        config: LiquidGlassConfig,
        isDark: Boolean,
        accentColor: Int,
    ): String {
        val rgbColor = resolveTintColor(config.tintSwatch, isDark, 0, accentColor)
        val r = Color.red(rgbColor)
        val g = Color.green(rgbColor)
        val b = Color.blue(rgbColor)
        val thin = if (isDark) 0.16f else 0.06f
        val dense = if (isDark) 0.38f else 0.22f
        val alpha = (thin + (dense - thin) * config.density).coerceIn(0.04f, 0.90f)
        val catchA = ((if (isDark) 0.36f else 0.75f) * config.rimLight).coerceIn(0f, 1f)
        val bevelTop = ((if (isDark) 0.24f else 0.55f) * config.bevel).coerceIn(0f, 1f)
        val shadowA = ((if (isDark) 0.28f else 0.08f) * config.shadow).coerceIn(0f, 1f)

        return buildString {
            appendLine("/* Solina Launcher • Liquid Glass (Glasswork) */")
            appendLine(":root {")
            appendLine("  --m-tint: rgba($r, $g, $b, ${"%.2f".format(java.util.Locale.US, alpha)});")
            appendLine("  --m-blur: ${config.blurPx}px;")
            appendLine("  --m-catch: ${"%.2f".format(java.util.Locale.US, catchA)};")
            appendLine("  --m-bevel: ${"%.2f".format(java.util.Locale.US, bevelTop)};")
            appendLine("  --m-refraction: ${"%.2f".format(java.util.Locale.US, config.refraction)};")
            appendLine("  --m-dispersion: ${"%.2f".format(java.util.Locale.US, config.dispersion)};")
            appendLine("  --m-rim-width: ${config.rimWidthDp}px;")
            appendLine("}")
            appendLine(".liquid-glass {")
            appendLine("  background: var(--m-tint);")
            appendLine("  backdrop-filter: blur(var(--m-blur)) saturate(1.18);")
            appendLine("  -webkit-backdrop-filter: blur(var(--m-blur)) saturate(1.18);")
            appendLine("  box-shadow:")
            appendLine("    inset 1.5px 1.5px 0 0 rgba(255, 255, 255, var(--m-catch)),")
            appendLine("    inset -1.5px -1.5px 0 0 rgba(255, 255, 255, calc(var(--m-catch) * 0.6)),")
            appendLine("    inset 0 6px 8px -6px rgba(255, 255, 255, var(--m-bevel)),")
            appendLine("    0 12px 32px -8px rgba(0, 0, 0, ${"%.2f".format(java.util.Locale.US, shadowA)});")
            append("}")
        }
    }
}
