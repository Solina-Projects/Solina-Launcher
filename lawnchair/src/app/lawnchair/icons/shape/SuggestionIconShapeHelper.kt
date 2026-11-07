package app.lawnchair.icons.shape

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.android.launcher3.BubbleTextView
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherAppState
import com.android.launcher3.Utilities
import com.android.launcher3.graphics.ThemeManager
import com.android.launcher3.icons.FastBitmapDrawable
import com.android.launcher3.model.data.ItemInfoWithIcon

/**
 * Applies an independent, dedicated [IconShape] to Suggested Apps
 * ([com.android.launcher3.appprediction.PredictionRowView]) icons without mutating or depending
 * on the global launcher icon shape.
 *
 * The cached launcher bitmaps already have the global shape baked in, so masking them can only
 * clip them further. To make the dedicated shape actually visible, the icon is re-rendered from
 * its source adaptive icon (themed when themed icons are on) with the dedicated mask. If anything
 * goes wrong the icon that is already bound to the view is left untouched, so a slot never ends up
 * blank.
 */
object SuggestionIconShapeHelper {

    private val maskedBitmapCache = object : LruCache<String, Bitmap>(64) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    @JvmStatic
    fun clearCache() {
        maskedBitmapCache.evictAll()
    }

    @JvmStatic
    fun applySuggestionIconShape(
        bubbleTextView: BubbleTextView,
        iconShape: IconShape?,
        iconSizePx: Int,
    ) {
        if (iconShape == null || iconSizePx <= 0) return
        try {
            apply(bubbleTextView, iconShape, iconSizePx)
        } catch (_: Throwable) {
            // Keep whatever icon is already bound; never leave an empty slot.
        }
    }

    private fun apply(bubbleTextView: BubbleTextView, iconShape: IconShape, iconSizePx: Int) {
        val itemInfo = bubbleTextView.tag as? ItemInfoWithIcon ?: return
        val infoBitmap = itemInfo.bitmap?.icon
        // A tiny bitmap is the shared low-res placeholder used while the real icon is still
        // loading. The high-res reapply runs this helper again once the real icon arrives.
        if (infoBitmap == null || infoBitmap.isRecycled || infoBitmap.width <= 8 || infoBitmap.height <= 8) return

        val context = bubbleTextView.context
        val themed = bubbleTextView.shouldUseTheme()
        val cacheKey = "${itemInfo.targetComponent}|${itemInfo.user}|$iconShape|$iconSizePx|$themed|${infoBitmap.generationId}"

        val shaped = maskedBitmapCache.get(cacheKey) ?: run {
            val rendered = renderFromSource(context, itemInfo, iconShape, iconSizePx, themed)
                ?: renderFromBitmap(infoBitmap, iconShape, iconSizePx)
                ?: return
            maskedBitmapCache.put(cacheKey, rendered)
            rendered
        }

        val drawable = FastBitmapDrawable(shaped)
        drawable.setBounds(0, 0, iconSizePx, iconSizePx)
        bubbleTextView.setCustomIconDrawable(drawable)
    }

    private fun scaledMask(iconShape: IconShape, sizePx: Int): Path {
        val path = Path(iconShape.getMaskPath())
        // IconShape mask paths are defined in a 100x100 coordinate space.
        path.transform(Matrix().apply { setScale(sizePx / 100f, sizePx / 100f) })
        return path
    }

    private fun applyMask(out: Bitmap, iconShape: IconShape, sizePx: Int) {
        val maskBitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFFFFFF.toInt()
        }
        Canvas(maskBitmap).drawPath(scaledMask(iconShape, sizePx), fill)
        Canvas(out).drawBitmap(maskBitmap, 0f, 0f, maskPaint)
        maskBitmap.recycle()
    }

    /** Re-renders the real app icon with the dedicated mask. Returns null if it is not possible. */
    private fun renderFromSource(
        context: Context,
        info: ItemInfoWithIcon,
        iconShape: IconShape,
        sizePx: Int,
        themed: Boolean,
    ): Bitmap? {
        val intent = info.intent ?: return null
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
        val activityInfo = launcherApps.resolveActivity(intent, info.user) ?: return null
        val dpi = InvariantDeviceProfile.INSTANCE.get(context).fillResIconDpi
        val provider = LauncherAppState.getInstance(context).iconProvider
        val source: Drawable = if (Utilities.ATLEAST_S) {
            provider.getIcon(activityInfo.activityInfo, dpi)
        } else {
            provider.getIcon(activityInfo.applicationInfo, dpi)
        } ?: return null

        var adaptive = source as? AdaptiveIconDrawable ?: return null
        if (themed && Utilities.ATLEAST_T) {
            val controller = ThemeManager.INSTANCE.get(context).themeController
            if (controller != null) {
                adaptive = controller.createThemedAdaptiveIcon(context, adaptive, info.bitmap) ?: adaptive
            }
        }

        val bg = adaptive.background
        val fg = adaptive.foreground
        if (bg == null && fg == null) return null

        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // Adaptive layers are 1.5x the visible viewport, centered.
        val inset = (-sizePx * 0.25f).toInt()
        val layerBounds = intArrayOf(inset, inset, sizePx - inset, sizePx - inset)
        for (layer in listOf(bg, fg)) {
            layer ?: continue
            layer.setBounds(layerBounds[0], layerBounds[1], layerBounds[2], layerBounds[3])
            layer.draw(canvas)
        }
        applyMask(out, iconShape, sizePx)
        return out
    }

    /** Fallback: mask the already cached bitmap (copying it first if it lives in GPU memory). */
    private fun renderFromBitmap(source: Bitmap, iconShape: IconShape, sizePx: Int): Bitmap? {
        val readable = if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            source
        }
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val matrix = Matrix().apply {
            setScale(sizePx.toFloat() / readable.width, sizePx.toFloat() / readable.height)
        }
        Canvas(out).drawBitmap(readable, matrix, bitmapPaint)
        if (readable !== source) readable.recycle()
        applyMask(out, iconShape, sizePx)
        return out
    }
}
