package app.lawnchair.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.icu.text.SimpleDateFormat
import android.icu.util.PersianCalendar
import android.widget.RemoteViews
import com.android.launcher3.R
import java.util.Date
import java.util.Locale

/**
 * A lightweight home-screen widget showing the current Solar Hijri date.
 * Android ICU supplies the Persian calendar implementation, avoiding a
 * third-party date-conversion dependency.
 */
class PersianCalendarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: android.content.Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            IntentActions.DATE_CHANGED,
            IntentActions.TIME_CHANGED,
            IntentActions.TIMEZONE_CHANGED,
            IntentActions.LOCALE_CHANGED -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(
                    android.content.ComponentName(context, PersianCalendarWidgetProvider::class.java),
                )
                ids.forEach { updateWidget(context, manager, it) }
            }
        }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.solina_persian_calendar_widget)
        val locale = Locale("fa", "IR")
        val formatter = SimpleDateFormat("EEEE، d MMMM yyyy", locale).apply {
            calendar = PersianCalendar(locale)
        }
        views.setTextViewText(R.id.solina_persian_calendar_date, formatter.format(Date()))
        views.setTextViewText(R.id.solina_persian_calendar_caption, context.getString(R.string.solina_persian_calendar_caption))
        manager.updateAppWidget(widgetId, views)
    }

    private object IntentActions {
        const val DATE_CHANGED = "android.intent.action.DATE_CHANGED"
        const val TIME_CHANGED = "android.intent.action.TIME_SET"
        const val TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED"
        const val LOCALE_CHANGED = "android.intent.action.LOCALE_CHANGED"
    }
}
