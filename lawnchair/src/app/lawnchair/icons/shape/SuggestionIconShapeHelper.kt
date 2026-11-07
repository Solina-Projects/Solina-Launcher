package app.lawnchair.icons.shape

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.LruCache
import com.android.launcher3.BubbleTextView
import com.android.launcher3.icons.FastBitmapDrawable
import com.android.launcher3.model.data.ItemInfoWithIcon

/**
 * Applies an independent, dedicated [IconShape] mask exclusively to Suggested Apps
 * ([com.android.launcher3.appprediction.PredictionRowView]) icons without mutating or depending
 * on the global launcher icon shape.
 */
object SuggestionIconShapeHelper {

    private val maskedBitmapCache = object : LruCache<String, Bitmap>(48) {
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
        val itemInfo = bubbleTextView.tag as? ItemInfoWithIcon
        val infoBitmap = itemInfo?.bitmap?.icon
        val currentIcon = bubbleTextView.icon
        if (infoBitmap == null && currentIcon == null) return
        // A tiny bitmap is the shared low-res placeholder used while the real icon is still loading.
        // Masking it would produce (and cache) a blank circle, so leave the icon alone; the
        // high-res reapply re-runs this helper once the real icon arrives.
        if (infoBitmap != null && (infoBitmap.width <= 8 || infoBitmap.height <= 8)) return

        val genId = infoBitmap?.generationId ?: System.identityHashCode(currentIcon)
        val tagKey = itemInfo?.componentKey?.toString() ?: bubbleTextView.text?.toString().orEmpty()
        val shapeKey = iconShape.toString()
        val cacheKey = "$tagKey|$shapeKey|$iconSizePx|$genId"

        val maskedBitmap = maskedBitmapCache.get(cacheKey) ?: run {
            val out = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
            val maskBitmap = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)

            val path = Path(iconShape.getMaskPath())
            val scaleMatrix = Matrix()
            // IconShape mask paths are defined in a 100x100 coordinate space.
            scaleMatrix.setScale(iconSizePx / 100f, iconSizePx / 100f)
            path.transform(scaleMatrix)

            val maskCanvas = Canvas(maskBitmap)
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = 0xFFFFFFFF.toInt()
            }
            maskCanvas.drawPath(path, fillPaint)

            val outCanvas = Canvas(out)
            if (infoBitmap != null && !infoBitmap.isRecycled && infoBitmap.width > 0 && infoBitmap.height > 0) {
                val srcMatrix = Matrix()
                srcMatrix.setScale(
                    iconSizePx.toFloat() / infoBitmap.width.toFloat(),
                    iconSizePx.toFloat() / infoBitmap.height.toFloat(),
                )
                outCanvas.drawBitmap(infoBitmap, srcMatrix, bitmapPaint)
            } else if (currentIcon != null) {
                val oldBounds = currentIcon.copyBounds()
                currentIcon.setBounds(0, 0, iconSizePx, iconSizePx)
                currentIcon.draw(outCanvas)
                currentIcon.bounds = oldBounds
            }
            outCanvas.drawBitmap(maskBitmap, 0f, 0f, maskPaint)
            maskBitmap.recycle()

            if (infoBitmap != null) {
                // Only cache masks built from the item's real bitmap, never from a transient drawable.
                maskedBitmapCache.put(cacheKey, out)
            }
            out
        }

        val shapedDrawable = FastBitmapDrawable(maskedBitmap)
        shapedDrawable.setBounds(0, 0, iconSizePx, iconSizePx)
        bubbleTextView.setCustomIconDrawable(shapedDrawable)
    }
}
