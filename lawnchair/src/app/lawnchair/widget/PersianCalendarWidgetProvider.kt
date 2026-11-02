package app.lawnchair.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.icu.text.SimpleDateFormat
import android.widget.RemoteViews
import com.android.launcher3.R
import java.util.Date
import java.util.Locale

/**
 * A lightweight home-screen widget showing today's Solar Hijri date.
 * The conversion is local and does not require network access or a third-party library.
 */
class PersianCalendarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in setOf(
                IntentActions.DATE_CHANGED,
                IntentActions.TIME_CHANGED,
                IntentActions.TIMEZONE_CHANGED,
                IntentActions.LOCALE_CHANGED,
            )
        ) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, PersianCalendarWidgetProvider::class.java),
            )
            ids.forEach { updateWidget(context, manager, it) }
        }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.solina_persian_calendar_widget)
        val now = Date()
        val locale = Locale("fa", "IR")
        val gregorian = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now).split("-").map { it.toInt() }
        val (year, month, day) = gregorianToJalali(gregorian[0], gregorian[1], gregorian[2])
        val monthNames = listOf(
            "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
        )
        val weekday = SimpleDateFormat("EEEE", locale).format(now)
        val dateText = "$weekday، $day ${monthNames[month - 1]} $year".toPersianDigits()
        views.setTextViewText(R.id.solina_persian_calendar_date, dateText)
        views.setTextViewText(
            R.id.solina_persian_calendar_caption,
            context.getString(R.string.solina_persian_calendar_caption),
        )
        manager.updateAppWidget(widgetId, views)
    }

    private fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val monthOffsets = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        var jy = if (gy > 1600) 979 else 0
        val adjustedYear = if (gy > 1600) gy - 1600 else gy - 621
        val gy2 = if (gm > 2) adjustedYear + 1 else adjustedYear
        var days = 365 * adjustedYear +
            (gy2 + 3) / 4 -
            (gy2 + 99) / 100 +
            (gy2 + 399) / 400 -
            80 + gd + monthOffsets[gm - 1]
        jy += 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        return Triple(jy, jm, jd)
    }

    private fun String.toPersianDigits(): String {
        val digits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        return map { char -> if (char in '0'..'9') digits[char - '0'] else char }.joinToString("")
    }

    private object IntentActions {
        const val DATE_CHANGED = "android.intent.action.DATE_CHANGED"
        const val TIME_CHANGED = "android.intent.action.TIME_SET"
        const val TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED"
        const val LOCALE_CHANGED = "android.intent.action.LOCALE_CHANGED"
    }
}
