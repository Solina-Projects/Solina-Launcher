package app.lawnchair.allapps

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.theme.color.tokens.AllAppsTabColors
import app.lawnchair.theme.color.tokens.ColorTokens
import app.lawnchair.util.PersianCalendarUtils
import com.android.launcher3.R
import java.util.Locale

data class DrawerCategoryChip(
    val id: String,
    val title: String,
    val count: Int,
)

class AllAppsCategoryBar(context: Context) : HorizontalScrollView(context) {

    private val prefs = PreferenceManager.getInstance(context)
    private val prefs2 = PreferenceManager2.getInstance(context)
    private val chipRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private var lastSignature: String = ""

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        val density = resources.displayMetrics.density
        val hPad = (12f * density).toInt()
        val vPad = (6f * density).toInt()
        setPadding(hPad, vPad, hPad, vPad)
        addView(
            chipRow,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    fun bindCategories(
        chips: List<DrawerCategoryChip>,
        selectedId: String,
        onSelect: (String) -> Unit,
    ) {
        val style = prefs.drawerCategoryBarStyle.get()
        val showCounts = prefs.drawerCaddyShowCounts.get()
        val usePersianDigits = prefs2.smartspacePersianNumerals.firstCached() ||
            Locale.getDefault().language == "fa"
        val signature = buildString {
            append(style).append('|').append(showCounts).append('|')
            append(selectedId).append('|').append(usePersianDigits).append('|')
            chips.forEach { append(it.id).append(':').append(it.title).append(':').append(it.count).append(';') }
        }
        if (signature == lastSignature && chipRow.childCount == chips.size) {
            return
        }
        lastSignature = signature
        chipRow.removeAllViews()

        val density = resources.displayMetrics.density
        val accentColor = ColorTokens.AllAppsTabBackgroundSelected.resolveColor(context)
        val surfaceColor = ColorTokens.SurfaceContainerHighest.resolveColor(context)
        val textPrimary = ColorTokens.TextColorPrimary.resolveColor(context)
        val textSecondary = ColorTokens.TextColorSecondary.resolveColor(context)
        val selectedTextColor = if (ColorUtils.calculateLuminance(accentColor) > 0.5) {
            Color.rgb(20, 22, 28)
        } else {
            Color.WHITE
        }

        if (style == "tonal_segmented") {
            val trackBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * density
                setColor(ColorUtils.setAlphaComponent(surfaceColor, 145))
            }
            val trackPad = (4f * density).toInt()
            chipRow.background = trackBg
            chipRow.setPadding(trackPad, trackPad, trackPad, trackPad)
        } else {
            chipRow.background = null
            chipRow.setPadding(0, 0, 0, 0)
        }

        chips.forEachIndexed { index, chip ->
            val isSelected = chip.id == selectedId
            val chipContainer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val hInner = (14f * density).toInt()
                val vInner = (8f * density).toInt()
                setPadding(hInner, vInner, hInner, vInner)
                background = buildChipDrawable(
                    style = style,
                    isSelected = isSelected,
                    accentColor = accentColor,
                    surfaceColor = surfaceColor,
                    density = density,
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val targetId = if (isSelected && chip.id != CATEGORY_ALL) {
                        CATEGORY_ALL
                    } else {
                        chip.id
                    }
                    onSelect(targetId)
                }
            }

            val titleView = TextView(context).apply {
                text = chip.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                typeface = Typeface.create(
                    typeface,
                    if (isSelected) Typeface.BOLD else Typeface.NORMAL,
                )
                setTextColor(
                    when {
                        style == "neon_chips" && isSelected -> accentColor
                        style == "classic_tabs" && isSelected -> accentColor
                        isSelected -> selectedTextColor
                        else -> textPrimary
                    },
                )
            }
            chipContainer.addView(titleView)

            if (showCounts && chip.count > 0) {
                val rawCount = chip.count.toString()
                val formattedCount = if (usePersianDigits) {
                    PersianCalendarUtils.toPersianDigits(rawCount)
                } else {
                    rawCount
                }
                val badgeView = TextView(context).apply {
                    text = formattedCount
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    typeface = Typeface.create(typeface, Typeface.BOLD)
                    val bH = (6f * density).toInt()
                    val bV = (2f * density).toInt()
                    setPadding(bH, bV, bH, bV)
                    val badgeBg = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 999f
                        val badgeColor = if (isSelected && style != "neon_chips" && style != "classic_tabs") {
                            ColorUtils.setAlphaComponent(selectedTextColor, 45)
                        } else {
                            ColorUtils.setAlphaComponent(accentColor, 38)
                        }
                        setColor(badgeColor)
                    }
                    background = badgeBg
                    setTextColor(
                        if (isSelected && style != "neon_chips" && style != "classic_tabs") {
                            selectedTextColor
                        } else {
                            textSecondary
                        },
                    )
                }
                val badgeLp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = (6f * density).toInt()
                }
                chipContainer.addView(badgeView, badgeLp)
            }

            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (index < chips.lastIndex) {
                    marginEnd = (8f * density).toInt()
                }
            }
            chipRow.addView(chipContainer, lp)
        }
    }

    private fun buildChipDrawable(
        style: String,
        isSelected: Boolean,
        accentColor: Int,
        surfaceColor: Int,
        density: Float,
    ): Drawable {
        return when (style) {
            "tonal_segmented" -> GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                if (isSelected) {
                    setColor(accentColor)
                } else {
                    setColor(Color.TRANSPARENT)
                }
            }

            "neon_chips" -> GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                if (isSelected) {
                    setColor(ColorUtils.setAlphaComponent(accentColor, 36))
                    setStroke(Math.max(2, (1.8f * density).toInt()), accentColor)
                } else {
                    setColor(ColorUtils.setAlphaComponent(surfaceColor, 140))
                    setStroke(
                        Math.max(1, (1f * density).toInt()),
                        ColorUtils.setAlphaComponent(accentColor, 65),
                    )
                }
            }

            "classic_tabs" -> GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                if (isSelected) {
                    setColor(ColorUtils.setAlphaComponent(accentColor, 32))
                    setStroke(Math.max(1, (1.4f * density).toInt()), accentColor)
                } else {
                    setColor(Color.TRANSPARENT)
                }
            }

            else -> {
                // "floating_glass"
                val baseColor = if (isSelected) accentColor else surfaceColor
                val topAlpha = if (isSelected) 235 else 185
                val bottomAlpha = if (isSelected) 200 else 135
                val topColor = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(baseColor, Color.WHITE, 0.16f),
                    topAlpha,
                )
                val bottomColor = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(baseColor, Color.BLACK, 0.08f),
                    bottomAlpha,
                )
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(topColor, bottomColor),
                ).apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 999f
                    val borderColor = if (isSelected) {
                        ColorUtils.setAlphaComponent(Color.WHITE, 150)
                    } else {
                        ColorUtils.setAlphaComponent(Color.WHITE, 75)
                    }
                    setStroke(Math.max(1, (1.1f * density).toInt()), borderColor)
                }
            }
        }
    }

    companion object {
        const val CATEGORY_ALL = "__ALL__"
    }
}

object AllAppsCardStyler {

    private const val TAG_HEADER_TITLE = 0x7f0b0001
    private const val TAG_HEADER_COUNT = 0x7f0b0002
    private const val TAG_HEADER_DOT = 0x7f0b0003

    @JvmStatic
    fun attachCategoryBar(host: ViewGroup, barView: View?) {
        if (barView == null) {
            host.removeAllViews()
            return
        }
        if (barView.parent === host) {
            return
        }
        (barView.parent as? ViewGroup)?.removeView(barView)
        host.removeAllViews()
        host.addView(
            barView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    @JvmStatic
    fun createCategoryHeaderView(context: Context): View {
        val density = context.resources.displayMetrics.density
        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val hMargin = (14f * density).toInt()
            val topPad = (10f * density).toInt()
            val bottomPad = (4f * density).toInt()
            setPadding(hMargin, topPad, hMargin, bottomPad)
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (14f * density).toInt()
            val vPad = (9f * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
        }

        val dotView = View(context).apply {
            tag = TAG_HEADER_DOT
            val w = (4f * density).toInt()
            val h = (16f * density).toInt()
            layoutParams = LinearLayout.LayoutParams(w, h).apply {
                marginEnd = (10f * density).toInt()
            }
        }
        row.addView(dotView)

        val titleView = TextView(context).apply {
            tag = TAG_HEADER_TITLE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        row.addView(titleView)

        val countView = TextView(context).apply {
            tag = TAG_HEADER_COUNT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create(typeface, Typeface.BOLD)
            val cH = (8f * density).toInt()
            val cV = (2.5f * density).toInt()
            setPadding(cH, cV, cH, cV)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = (8f * density).toInt()
            }
        }
        row.addView(countView)

        root.addView(
            row,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return root
    }

    @JvmStatic
    fun bindCategoryHeaderView(
        view: View,
        title: CharSequence?,
        count: Int,
        context: Context,
    ) {
        val root = view as? FrameLayout ?: return
        val row = root.getChildAt(0) as? LinearLayout ?: return
        val dotView = row.findViewWithTag<View>(TAG_HEADER_DOT)
        val titleView = row.findViewWithTag<TextView>(TAG_HEADER_TITLE)
        val countView = row.findViewWithTag<TextView>(TAG_HEADER_COUNT)

        val prefs = PreferenceManager.getInstance(context)
        val prefs2 = PreferenceManager2.getInstance(context)
        val density = context.resources.displayMetrics.density
        val cardStyle = prefs.drawerCaddyCardStyle.get()
        val radiusPx = prefs.drawerCaddyCardRadius.get().coerceIn(8, 32) * density
        val showCounts = prefs.drawerCaddyShowCounts.get()
        val usePersianDigits = prefs2.smartspacePersianNumerals.firstCached() ||
            Locale.getDefault().language == "fa"

        val accentColor = ColorTokens.AllAppsTabBackgroundSelected.resolveColor(context)
        val surfaceColor = ColorTokens.SurfaceContainerHighest.resolveColor(context)
        val textPrimary = ColorTokens.TextColorPrimary.resolveColor(context)

        dotView?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(accentColor)
        }

        titleView?.text = title ?: ""
        titleView?.setTextColor(textPrimary)

        if (showCounts && count > 0) {
            countView?.visibility = View.VISIBLE
            val rawCount = count.toString()
            countView?.text = if (usePersianDigits) {
                PersianCalendarUtils.toPersianDigits(rawCount)
            } else {
                rawCount
            }
            countView?.setTextColor(accentColor)
            countView?.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                setColor(ColorUtils.setAlphaComponent(accentColor, 34))
            }
        } else {
            countView?.visibility = View.GONE
        }

        row.background = buildPodDrawable(
            style = if (cardStyle == "none") "tonal_pod" else cardStyle,
            surfaceColor = surfaceColor,
            accentColor = accentColor,
            radiusPx = (radiusPx * 0.75f).coerceAtLeast(10f * density),
            density = density,
        )
    }

    @JvmStatic
    fun styleFolderContainer(container: ViewGroup, context: Context) {
        val prefs = PreferenceManager.getInstance(context)
        val style = prefs.drawerCaddyCardStyle.get()
        val density = context.resources.displayMetrics.density
        if (style == "none") {
            container.background = null
            container.setPadding(0, 0, 0, 0)
            return
        }
        val radiusPx = prefs.drawerCaddyCardRadius.get().coerceIn(8, 32) * density
        val surfaceColor = ColorTokens.SurfaceContainerHighest.resolveColor(context)
        val accentColor = ColorTokens.AllAppsTabBackgroundSelected.resolveColor(context)
        val inset = (4f * density).toInt()
        val rawDrawable = buildPodDrawable(
            style = style,
            surfaceColor = surfaceColor,
            accentColor = accentColor,
            radiusPx = radiusPx,
            density = density,
        )
        if (rawDrawable != null) {
            val insetLayer = LayerDrawable(arrayOf(rawDrawable)).apply {
                setLayerInset(0, inset, inset / 2, inset, inset / 2)
            }
            container.background = insetLayer
        } else {
            container.background = null
        }
    }

    @JvmStatic
    fun styleAppIconView(iconView: View, context: Context) {
        val prefs = PreferenceManager.getInstance(context)
        if (!prefs.drawerApplyCardToApps.get()) {
            iconView.background = null
            return
        }
        val style = prefs.drawerCaddyCardStyle.get()
        if (style == "none") {
            iconView.background = null
            return
        }
        val density = context.resources.displayMetrics.density
        val radiusPx = prefs.drawerCaddyCardRadius.get().coerceIn(8, 32) * density
        val surfaceColor = ColorTokens.SurfaceContainerHighest.resolveColor(context)
        val accentColor = ColorTokens.AllAppsTabBackgroundSelected.resolveColor(context)
        val inset = (4f * density).toInt()
        val rawDrawable = buildPodDrawable(
            style = style,
            surfaceColor = surfaceColor,
            accentColor = accentColor,
            radiusPx = radiusPx,
            density = density,
            alphaFactor = 0.65f,
        )
        if (rawDrawable != null) {
            iconView.background = LayerDrawable(arrayOf(rawDrawable)).apply {
                setLayerInset(0, inset, inset / 2, inset, inset / 2)
            }
        } else {
            iconView.background = null
        }
    }

    private fun buildPodDrawable(
        style: String,
        surfaceColor: Int,
        accentColor: Int,
        radiusPx: Float,
        density: Float,
        alphaFactor: Float = 1f,
    ): Drawable? {
        return when (style) {
            "tonal_pod" -> GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radiusPx
                val blended = ColorUtils.blendARGB(surfaceColor, accentColor, 0.10f)
                setColor(ColorUtils.setAlphaComponent(blended, (145 * alphaFactor).toInt()))
            }

            "glass_card" -> {
                val topColor = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(surfaceColor, Color.WHITE, 0.18f),
                    (175 * alphaFactor).toInt(),
                )
                val bottomColor = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(surfaceColor, accentColor, 0.12f),
                    (130 * alphaFactor).toInt(),
                )
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(topColor, bottomColor),
                ).apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radiusPx
                    setStroke(
                        Math.max(1, (1.1f * density).toInt()),
                        ColorUtils.setAlphaComponent(Color.WHITE, (90 * alphaFactor).toInt()),
                    )
                }
            }

            "elevated_3d" -> {
                val thickness = Math.max(2, (3f * density).toInt())
                val edge = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radiusPx
                    setColor(
                        ColorUtils.setAlphaComponent(
                            ColorUtils.blendARGB(surfaceColor, Color.BLACK, 0.35f),
                            (185 * alphaFactor).toInt(),
                        ),
                    )
                }
                val faceTop = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(surfaceColor, Color.WHITE, 0.22f),
                    (200 * alphaFactor).toInt(),
                )
                val faceBottom = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(surfaceColor, accentColor, 0.08f),
                    (175 * alphaFactor).toInt(),
                )
                val face = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(faceTop, faceBottom),
                ).apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radiusPx
                    setStroke(
                        Math.max(1, density.toInt()),
                        ColorUtils.setAlphaComponent(Color.WHITE, (105 * alphaFactor).toInt()),
                    )
                }
                LayerDrawable(arrayOf(edge, face)).apply {
                    setLayerInset(1, 0, 0, 0, thickness)
                }
            }

            else -> null
        }
    }

    @JvmStatic
    fun localizeCategoryTitle(context: Context, rawTitle: String): String {
        val cleaned = rawTitle.removePrefix("Solina • ").trim()
        return when (cleaned.lowercase(Locale.ROOT)) {
            "system apps", "system" -> context.getString(R.string.drawer_category_system_apps)

            "google apps", "google" -> context.getString(R.string.drawer_category_google_apps)

            "social", "social & media" -> context.getString(R.string.smart_category_social)

            "communication", "messaging" -> context.getString(R.string.smart_category_communication)

            "games", "gaming" -> context.getString(R.string.smart_category_games)

            "media", "multimedia", "audio & video", "music & audio", "video" ->
                context.getString(R.string.smart_category_media)

            "shopping", "finance" -> context.getString(R.string.smart_category_shopping)

            "productivity", "office" -> context.getString(R.string.smart_category_productivity)

            "tools", "utilities" -> context.getString(R.string.smart_category_tools)

            else -> rawTitle
        }
    }
}
