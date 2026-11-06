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
        val currentIcon = bubbleTextView.icon ?: return
        val sourceBitmap = (currentIcon as? FastBitmapDrawable)?.bitmap ?: return
        if (sourceBitmap.isRecycled || sourceBitmap.width <= 0 || sourceBitmap.height <= 0) return

        val tagKey = bubbleTextView.tag?.toString() ?: sourceBitmap.generationId.toString()
        val shapeKey = iconShape.toString()
        val cacheKey = "$tagKey|$shapeKey|$iconSizePx|${sourceBitmap.generationId}"

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
            val srcMatrix = Matrix()
            srcMatrix.setScale(
                iconSizePx.toFloat() / sourceBitmap.width.toFloat(),
                iconSizePx.toFloat() / sourceBitmap.height.toFloat(),
            )
            outCanvas.drawBitmap(sourceBitmap, srcMatrix, bitmapPaint)
            outCanvas.drawBitmap(maskBitmap, 0f, 0f, maskPaint)
            maskBitmap.recycle()

            maskedBitmapCache.put(cacheKey, out)
            out
        }

        val shapedDrawable = FastBitmapDrawable(maskedBitmap)
        shapedDrawable.setBounds(0, 0, iconSizePx, iconSizePx)
        bubbleTextView.setIconVisible(true)
        bubbleTextView.applyCompoundDrawables(shapedDrawable)
    }
}
