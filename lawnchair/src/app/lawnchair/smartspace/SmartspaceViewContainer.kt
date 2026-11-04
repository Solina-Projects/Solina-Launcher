package app.lawnchair.smartspace

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import app.lawnchair.SolinaLauncher
import app.lawnchair.launcher
import app.lawnchair.preferences.PreferenceChangeListener
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.navigation.Smartspace
import com.android.launcher3.CheckLongPressHelper
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.R
import com.android.launcher3.logging.StatsLogManager
import com.android.launcher3.util.Themes
import com.android.launcher3.views.OptionsPopupView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class SmartspaceViewContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val previewMode: Boolean = false,
) : FrameLayout(context, attrs) {

    private val longPressHelper = CheckLongPressHelper(this) { performLongClick() }
    private val smartspaceView: View
    private val prefs = PreferenceManager.getInstance(context)
    private val baseLeftPadding: Int
    private val baseRightPadding: Int
    private val styleListener = PreferenceChangeListener { applyCardStyle() }

    init {
        val inflater = LayoutInflater.from(context)
        smartspaceView = inflater.inflate(R.layout.smartspace_enhanced, this, false) as BcSmartspaceView
        smartspaceView.previewMode = previewMode
        val dp = InvariantDeviceProfile.INSTANCE.get(context).getDeviceProfile(context)
        baseLeftPadding = dp.widgetPadding.left
        baseRightPadding = dp.widgetPadding.right
        smartspaceView.setPadding(baseLeftPadding, top, baseRightPadding, bottom)

        setOnLongClickListener {
            openOptions()
            true
        }

        addView(smartspaceView)
        applyCardStyle()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.smartspaceCardStyle.addListener(styleListener)
        prefs.smartspaceCardAlpha.addListener(styleListener)
        prefs.smartspaceCardCornerRadius.addListener(styleListener)
        applyCardStyle()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        prefs.smartspaceCardStyle.removeListener(styleListener)
        prefs.smartspaceCardAlpha.removeListener(styleListener)
        prefs.smartspaceCardCornerRadius.removeListener(styleListener)
    }

    private fun dpToPx(dpValue: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dpValue,
        resources.displayMetrics,
    )

    private fun blendColor(from: Int, to: Int, ratio: Float, alpha: Int): Int {
        val inv = 1f - ratio
        val r = (Color.red(from) * inv + Color.red(to) * ratio).roundToInt().coerceIn(0, 255)
        val g = (Color.green(from) * inv + Color.green(to) * ratio).roundToInt().coerceIn(0, 255)
        val b = (Color.blue(from) * inv + Color.blue(to) * ratio).roundToInt().coerceIn(0, 255)
        return Color.argb(alpha.coerceIn(0, 255), r, g, b)
    }

    private fun applyCardStyle() {
        val style = prefs.smartspaceCardStyle.get()
        if (style == "transparent") {
            smartspaceView.background = null
            smartspaceView.setPadding(baseLeftPadding, top, baseRightPadding, bottom)
            return
        }

        val alphaPercent = prefs.smartspaceCardAlpha.get().coerceIn(15, 100)
        val alphaValue = (alphaPercent * 255) / 100
        val cornerRadiusPx = dpToPx(prefs.smartspaceCardCornerRadius.get().toFloat())
        val surfaceBase = Themes.getAttrColor(context, R.attr.qsbFillColor)
        val accentColor = Themes.getColorAccent(context)

        val drawable = GradientDrawable().apply {
            cornerRadius = cornerRadiusPx
            when (style) {
                "glass_card" -> {
                    val topColor = blendColor(surfaceBase, Color.WHITE, 0.24f, min(255, (alphaValue * 1.05f).roundToInt()))
                    val midColor = blendColor(surfaceBase, Color.WHITE, 0.06f, alphaValue)
                    val bottomColor = blendColor(surfaceBase, Color.BLACK, 0.14f, alphaValue)
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    colors = intArrayOf(topColor, midColor, bottomColor)
                    val borderAlpha = max(80, min(230, (alphaValue * 0.85f).roundToInt()))
                    setStroke(dpToPx(1.5f).roundToInt().coerceAtLeast(1), blendColor(surfaceBase, Color.WHITE, 0.48f, borderAlpha))
                }

                "tonal_capsule" -> {
                    val tonalFill = blendColor(surfaceBase, accentColor, 0.28f, alphaValue)
                    setColor(tonalFill)
                    val borderAlpha = max(90, min(240, (alphaValue * 0.9f).roundToInt()))
                    setStroke(dpToPx(1.5f).roundToInt().coerceAtLeast(1), blendColor(accentColor, Color.WHITE, 0.25f, borderAlpha))
                }

                "elevated_card" -> {
                    val topColor = blendColor(surfaceBase, Color.WHITE, 0.18f, alphaValue)
                    val bottomColor = blendColor(surfaceBase, Color.BLACK, 0.22f, alphaValue)
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    colors = intArrayOf(topColor, bottomColor)
                    setStroke(dpToPx(1f).roundToInt().coerceAtLeast(1), blendColor(surfaceBase, Color.WHITE, 0.32f, alphaValue))
                }

                else -> {
                    setColor(blendColor(surfaceBase, Color.WHITE, 0.05f, alphaValue))
                }
            }
        }
        val extraHorizontal = dpToPx(12f).roundToInt()
        val extraVertical = dpToPx(4f).roundToInt()
        smartspaceView.background = drawable
        smartspaceView.setPadding(
            baseLeftPadding + extraHorizontal,
            extraVertical,
            baseRightPadding + extraHorizontal,
            extraVertical,
        )
    }

    private fun openOptions() {
        if (previewMode) return

        val launcher = context.launcher
        val pos = Rect()
        launcher.dragLayer.getDescendantRectRelativeToSelf(smartspaceView, pos)
        OptionsPopupView.show<SolinaLauncher>(launcher, RectF(pos), listOf(getCustomizeOption()), true)
    }

    private fun getCustomizeOption() = OptionsPopupView.OptionItem(
        context,
        R.string.action_customize,
        R.drawable.ic_setting,
        StatsLogManager.LauncherEvent.IGNORE,
    ) {
        context.startActivity(PreferenceActivity.createIntent(context, Smartspace))
        true
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        longPressHelper.onTouchEvent(ev)
        return longPressHelper.hasPerformedLongPress()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        longPressHelper.onTouchEvent(ev)
        return true
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        longPressHelper.cancelLongPress()
    }
}
