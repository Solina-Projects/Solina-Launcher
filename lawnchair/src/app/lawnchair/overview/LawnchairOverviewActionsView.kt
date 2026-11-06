package app.lawnchair.overview

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Space
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import app.lawnchair.glass.LiquidGlassStyleHelper
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.theme.color.tokens.ColorTokens
import app.lawnchair.util.RecentHelper
import app.lawnchair.util.isOnePlusStock
import com.android.launcher3.R
import com.android.quickstep.views.OverviewActionsView
import kotlin.math.roundToInt

class LawnchairOverviewActionsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : OverviewActionsView<TaskOverlayFactoryImpl.OverlayUICallbacks>(context, attrs, defStyleAttr) {

    private val prefs = PreferenceManager.getInstance(context)
    private lateinit var container: LinearLayout
    private lateinit var screenshotAction: Button
    private lateinit var shareAction: Button
    private lateinit var lensAction: Button
    private lateinit var clearAllAction: Button
    private lateinit var lockedAction: Button
    private var clearAllListener: OnClickListener? = null

    override fun onFinishInflate() {
        super.onFinishInflate()

        container = ViewCompat.requireViewById(this, R.id.action_buttons)
        clearAllAction = ViewCompat.requireViewById(this, R.id.action_clear_all)
        shareAction = ViewCompat.requireViewById(this, R.id.action_share)
        lensAction = ViewCompat.requireViewById(this, R.id.action_lens)
        screenshotAction = ViewCompat.requireViewById(this, R.id.action_screenshot)
        lockedAction = ViewCompat.requireViewById(this, R.id.action_locked)

        shareAction.setOnClickListener { mCallbacks?.onShare() }
        lensAction.setOnClickListener { mCallbacks?.onLens() }
        lockedAction.setOnClickListener { mCallbacks?.onToggleLock() }
        clearAllAction.setOnClickListener { view ->
            val listener = clearAllListener
            if (listener != null) {
                listener.onClick(view)
            } else {
                RecentHelper.clearAllTaskStacks(context)
            }
        }

        prefs.recentsActionClearAll.subscribeChanges(this, ::updateVisibilities)
        prefs.recentsActionLens.subscribeChanges(this, ::updateVisibilities)
        prefs.recentsActionScreenshot.subscribeChanges(this, ::updateVisibilities)
        prefs.recentsActionShare.subscribeChanges(this, ::updateVisibilities)
        prefs.recentsActionLocked.subscribeChanges(this, ::updateVisibilities)
        prefs.recentActionOrder.subscribeChanges(this, ::updateVisibilities)
        prefs.recentsActionBarStyle.subscribeChanges(this, ::applyActionBarStyle)
        prefs.recentsActionBarRadius.subscribeChanges(this, ::applyActionBarStyle)
        prefs.liquidGlassEnabled.subscribeChanges(this, ::applyActionBarStyle)
        prefs.liquidGlassApplyRecents.subscribeChanges(this, ::applyActionBarStyle)
        prefs.liquidGlassPreset.subscribeChanges(this, ::applyActionBarStyle)

        updateVisibilities()
        applyActionBarStyle()
    }

    private fun dpToPx(dp: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp,
        resources.displayMetrics,
    )

    private fun applyActionBarStyle() {
        if (!::container.isInitialized) return
        val style = prefs.recentsActionBarStyle.get()
        val useLiquidGlass = LiquidGlassStyleHelper.shouldUseForRecents(context, style)
        if (style == "classic" && !useLiquidGlass) {
            container.background = null
            container.setPadding(0, 0, 0, 0)
            return
        }
        val radiusPx = dpToPx(prefs.recentsActionBarRadius.get().toFloat())
        val surfaceColor = ColorTokens.Surface.resolveColor(context)
        val accentColor = ColorTokens.ColorAccent.resolveColor(context)
        val bg: Drawable = if (useLiquidGlass) {
            LiquidGlassStyleHelper.createDrawable(
                context = context,
                cornerRadiusPx = radiusPx,
                fallbackColor = surfaceColor,
                alphaMultiplier = 1f,
            )
        } else {
            GradientDrawable().apply {
                cornerRadius = radiusPx
                when (style) {
                    "tonal_pill" -> {
                        val fill = ColorUtils.blendARGB(surfaceColor, accentColor, 0.22f)
                        setColor(ColorUtils.setAlphaComponent(fill, 228))
                        setStroke(
                            dpToPx(1.2f).roundToInt().coerceAtLeast(1),
                            ColorUtils.setAlphaComponent(accentColor, 140),
                        )
                    }

                    else -> {
                        val topColor = ColorUtils.setAlphaComponent(
                            ColorUtils.blendARGB(surfaceColor, Color.WHITE, 0.16f),
                            220,
                        )
                        val bottomColor = ColorUtils.setAlphaComponent(
                            ColorUtils.blendARGB(surfaceColor, Color.BLACK, 0.12f),
                            205,
                        )
                        orientation = GradientDrawable.Orientation.TOP_BOTTOM
                        colors = intArrayOf(topColor, bottomColor)
                        setStroke(
                            dpToPx(1.2f).roundToInt().coerceAtLeast(1),
                            ColorUtils.setAlphaComponent(
                                ColorUtils.blendARGB(surfaceColor, Color.WHITE, 0.42f),
                                165,
                            ),
                        )
                    }
                }
            }
        }
        val hPad = dpToPx(8f).roundToInt()
        val vPad = dpToPx(2f).roundToInt()
        container.background = bg
        container.setPadding(hPad, vPad, hPad, vPad)
    }

    private fun updateVisibilities() {
        val order = prefs.recentActionOrder.get().split(",").mapNotNull { it.trim().toIntOrNull() }
        val buttonMap = mutableMapOf<Int, View>()
        if (prefs.recentsActionScreenshot.get() && !isOnePlusStock) {
            buttonMap[0] = screenshotAction
        }
        if (prefs.recentsActionShare.get()) {
            buttonMap[1] = shareAction
        }
        if (prefs.recentsActionLens.get() && isLensAvailable()) {
            buttonMap[2] = lensAction
        }
        if (prefs.recentsActionLocked.get()) {
            buttonMap[3] = lockedAction
        }
        if (prefs.recentsActionClearAll.get()) {
            buttonMap[4] = clearAllAction
        }

        val buttonsInOrder = order.mapNotNull { buttonMap[it] }

        container.removeAllViews()
        container.addView(createSpace())
        buttonsInOrder.forEach { view ->
            view.isVisible = true
            container.addView(view)
            container.addView(createSpace())
        }
    }

    private fun isLensAvailable(): Boolean {
        val lensIntent = context.packageManager.getLaunchIntentForPackage("com.google.ar.lens")
        return lensIntent != null
    }

    private fun createSpace(): View {
        return Space(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1).apply { weight = 1f }
        }
    }

    override fun setClearAllClickListener(clearAllClickListener: OnClickListener?) {
        this.clearAllListener = clearAllClickListener
    }
}
