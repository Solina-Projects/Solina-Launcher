package app.lawnchair.smartspace

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.icu.text.DateFormat
import android.icu.text.DisplayContext
import android.os.SystemClock
import android.text.format.DateFormat.is24HourFormat
import android.util.AttributeSet
import android.util.TypedValue
import app.lawnchair.preferences.PreferenceChangeListener
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.smartspace.model.SmartspaceCalendar
import app.lawnchair.smartspace.model.SmartspaceTimeFormat
import app.lawnchair.util.broadcastReceiverFlow
import app.lawnchair.util.repeatOnAttached
import app.lawnchair.util.subscribeBlocking
import com.android.launcher3.R
import com.android.launcher3.util.Themes
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

typealias FormatterFunction = (Long) -> String

class IcuDateTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : DoubleShadowTextView(context, attrs) {

    private val prefs = PreferenceManager2.getInstance(context)
    private val prefs1 = PreferenceManager.getInstance(context)
    private val baseTextSizePx = textSize
    private val basePadLeft = paddingLeft
    private val basePadTop = paddingTop
    private val basePadRight = paddingRight
    private val basePadBottom = paddingBottom
    private val dateStyleListener = PreferenceChangeListener { applyDatePillAndTextScale() }
    private var calendar: SmartspaceCalendar? = null
    private lateinit var dateTimeOptions: DateTimeOptions
    private var formatterFunction: FormatterFunction? = null
    private var usePersianNumerals: Boolean = false
    private val ticker = this::onTimeTick

    init {
        repeatOnAttached {
            val calendarFlow = prefs.smartspaceCalendar.get()
            val optionsFlow = DateTimeOptions.fromPrefs(prefs)
            val persianNumeralsFlow = prefs.smartspacePersianNumerals.get()
            combine(calendarFlow, optionsFlow, persianNumeralsFlow) { calendar, options, persianNumerals ->
                Triple(calendar, options, persianNumerals)
            }.subscribeBlocking(this) {
                calendar = it.first
                dateTimeOptions = it.second
                usePersianNumerals = it.third
                onTimeChanged(true)
            }

            val intentFilter = IntentFilter()
            intentFilter.addAction(Intent.ACTION_TIME_CHANGED)
            intentFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED)
            intentFilter.addAction(Intent.ACTION_LOCALE_CHANGED)
            broadcastReceiverFlow(context, intentFilter)
                .onEach { onTimeChanged(it.action != Intent.ACTION_TIME_TICK) }
                .launchIn(this)
        }
    }

    private fun onTimeChanged(updateFormatter: Boolean) {
        if (isShown) {
            val timeText = getTimeText(updateFormatter)
            if (text != timeText) {
                textAlignment = if (shouldAlignToTextEnd()) TEXT_ALIGNMENT_TEXT_END else TEXT_ALIGNMENT_TEXT_START
                text = timeText
                contentDescription = timeText
            }
        } else if (updateFormatter) {
            formatterFunction = null
        }
    }

    private fun shouldAlignToTextEnd(): Boolean {
        val is24HourFormatManual = dateTimeOptions.timeFormat is SmartspaceTimeFormat.TwentyFourHourFormat
        val is24HourFormatOnSystem = dateTimeOptions.timeFormat is SmartspaceTimeFormat.FollowSystem && is24HourFormat(context)
        val is24HourFormat = is24HourFormatManual || is24HourFormatOnSystem
        val shouldNotAlignToEnd = dateTimeOptions.showTime && is24HourFormat && !dateTimeOptions.showDate
        return calendar == SmartspaceCalendar.Persian && !shouldNotAlignToEnd
    }

    private fun getTimeText(updateFormatter: Boolean): String {
        val formatter = getFormatterFunction(updateFormatter)
        return formatter(System.currentTimeMillis())
    }

    private fun getFormatterFunction(updateFormatter: Boolean): FormatterFunction {
        if (formatterFunction != null && !updateFormatter) {
            return formatterFunction!!
        }
        val formatter = when (calendar) {
            SmartspaceCalendar.Persian -> createPersianFormatter()
            SmartspaceCalendar.Lunar -> createLunarFormatter()
            else -> createGregorianFormatter()
        }
        formatterFunction = formatter
        return formatter
    }

    private fun createLunarFormatter(): FormatterFunction {
        var format: String
        if (dateTimeOptions.showTime) {
            format = context.getString(
                when {
                    dateTimeOptions.timeFormat is SmartspaceTimeFormat.TwelveHourFormat -> R.string.smartspace_icu_date_pattern_gregorian_time_12h
                    dateTimeOptions.timeFormat is SmartspaceTimeFormat.TwentyFourHourFormat -> R.string.smartspace_icu_date_pattern_gregorian_time
                    is24HourFormat(context) -> R.string.smartspace_icu_date_pattern_gregorian_time
                    else -> R.string.smartspace_icu_date_pattern_gregorian_time_12h
                },
            )
            if (dateTimeOptions.showDate) format += context.getString(R.string.smartspace_icu_date_pattern_gregorian_date)
        } else {
            format = context.getString(R.string.smartspace_icu_date_pattern_gregorian_wday_month_day_no_year)
        }

        val chineseLocaleBuilder = Locale.Builder()
            .setLocale(Locale.CHINESE)
            .setUnicodeLocaleKeyword("ca", "chinese")
        if (usePersianNumerals) {
            chineseLocaleBuilder.setUnicodeLocaleKeyword("nu", "arabext")
        }
        val chineseLocale = chineseLocaleBuilder.build()

        val formatter = DateFormat.getInstanceForSkeleton(format, chineseLocale)
        formatter.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE)
        return { formatter.format(it) }
    }

    private fun createPersianFormatter(): FormatterFunction {
        var format: String
        if (dateTimeOptions.showTime) {
            format = context.getString(
                when {
                    dateTimeOptions.timeFormat is SmartspaceTimeFormat.TwelveHourFormat -> R.string.smartspace_icu_date_pattern_gregorian_time_12h
                    dateTimeOptions.timeFormat is SmartspaceTimeFormat.TwentyFourHourFormat -> R.string.smartspace_icu_date_pattern_gregorian_time
                    is24HourFormat(context) -> R.string.smartspace_icu_date_pattern_gregorian_time
                    else -> R.string.smartspace_icu_date_pattern_gregorian_time_12h
                },
            )
            if (dateTimeOptions.showDate) format = context.getString(R.string.smartspace_icu_date_pattern_gregorian_date) + format
        } else {
            format = context.getString(R.string.smartspace_icu_date_pattern_gregorian_wday_month_day_no_year)
        }
        val persianLocale = Locale.Builder()
            .setLanguage("fa") // Farsi script and localized weekday/month names
            .setUnicodeLocaleKeyword("ca", "persian")
            .setUnicodeLocaleKeyword("nu", if (usePersianNumerals) "arabext" else "latn")
            .build()

        val formatter = DateFormat.getInstanceForSkeleton(format, persianLocale)
        return { formatter.format(it) }
    }

    private fun createGregorianFormatter(): FormatterFunction {
        var format: String
        if (dateTimeOptions.showTime) {
            format = context.getString(
                when {
                    dateTimeOptions.timeFormat is SmartspaceTimeFormat.TwelveHourFormat -> R.string.smartspace_icu_date_pattern_gregorian_time_12h
                    dateTimeOptions.timeFormat is SmartspaceTimeFormat.TwentyFourHourFormat -> R.string.smartspace_icu_date_pattern_gregorian_time
                    is24HourFormat(context) -> R.string.smartspace_icu_date_pattern_gregorian_time
                    else -> R.string.smartspace_icu_date_pattern_gregorian_time_12h
                },
            )
            if (dateTimeOptions.showDate) format += context.getString(R.string.smartspace_icu_date_pattern_gregorian_date)
        } else {
            format = context.getString(R.string.smartspace_icu_date_pattern_gregorian_wday_month_day_no_year)
        }
        val locale = if (usePersianNumerals) {
            Locale.Builder()
                .setLocale(Locale.getDefault())
                .setUnicodeLocaleKeyword("nu", "arabext")
                .build()
        } else {
            Locale.getDefault()
        }
        val formatter = DateFormat.getInstanceForSkeleton(format, locale)
        formatter.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE)
        return { formatter.format(it) }
    }

    private fun onTimeTick() {
        onTimeChanged(false)
        val uptimeMillis: Long = SystemClock.uptimeMillis()
        handler?.postAtTime(ticker, uptimeMillis + (1000 - uptimeMillis % 1000))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs1.smartspaceDatePillBadge.addListener(dateStyleListener)
        prefs1.smartspaceDateTextSizeScale.addListener(dateStyleListener)
        applyDatePillAndTextScale()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        prefs1.smartspaceDatePillBadge.removeListener(dateStyleListener)
        prefs1.smartspaceDateTextSizeScale.removeListener(dateStyleListener)
    }

    private fun applyDatePillAndTextScale() {
        val scalePercent = prefs1.smartspaceDateTextSizeScale.get().coerceIn(85, 135)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, baseTextSizePx * (scalePercent / 100f))

        val pillEnabled = prefs1.smartspaceDatePillBadge.get()
        if (!pillEnabled) {
            background = null
            setPadding(basePadLeft, basePadTop, basePadRight, basePadBottom)
            return
        }
        val density = resources.displayMetrics.density
        val accent = Themes.getColorAccent(context)
        val surface = Themes.getAttrColor(context, R.attr.qsbFillColor)
        val pillColor = Color.argb(
            185,
            ((Color.red(surface) * 0.72f) + (Color.red(accent) * 0.28f)).roundToInt().coerceIn(0, 255),
            ((Color.green(surface) * 0.72f) + (Color.green(accent) * 0.28f)).roundToInt().coerceIn(0, 255),
            ((Color.blue(surface) * 0.72f) + (Color.blue(accent) * 0.28f)).roundToInt().coerceIn(0, 255),
        )
        val strokeColor = Color.argb(
            160,
            ((Color.red(accent) * 0.65f) + (Color.red(Color.WHITE) * 0.35f)).roundToInt().coerceIn(0, 255),
            ((Color.green(accent) * 0.65f) + (Color.green(Color.WHITE) * 0.35f)).roundToInt().coerceIn(0, 255),
            ((Color.blue(accent) * 0.65f) + (Color.blue(Color.WHITE) * 0.35f)).roundToInt().coerceIn(0, 255),
        )
        background = GradientDrawable().apply {
            cornerRadius = 50f * density
            setColor(pillColor)
            setStroke((1.2f * density).roundToInt().coerceAtLeast(1), strokeColor)
        }
        val padH = (10f * density).roundToInt()
        val padV = (3f * density).roundToInt()
        setPadding(basePadLeft + padH, basePadTop + padV, basePadRight + padH, basePadBottom + padV)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        handler?.removeCallbacks(ticker)
        if (isVisible) {
            ticker()
        }
    }
}

data class DateTimeOptions(
    val showDate: Boolean,
    val showTime: Boolean,
    val timeFormat: SmartspaceTimeFormat,
) {
    companion object {
        fun fromPrefs(prefs: PreferenceManager2) = combine(
            prefs.smartspaceShowDate.get(),
            prefs.smartspaceShowTime.get(),
            prefs.smartspaceTimeFormat.get(),
        ) { showDate, showTime, timeFormat ->
            DateTimeOptions(showDate, showTime, timeFormat)
        }
    }
}
