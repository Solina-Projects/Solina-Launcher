package app.lawnchair.util

import android.content.Context
import android.graphics.Color
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.theme.color.ColorOption

/** The long-press menus that can be styled independently from General settings. */
enum class PopupKind(val id: String) {
    HOME_MENU("home_menu"),
    APP_DRAWER("app_drawer"),
    APP_HOME("app_home"),
    APP_DOCK("app_dock"),
}

/**
 * Resolved look of one long-press menu. [backgroundColor] and [textColor] are null when the user
 * left them on automatic, in which case the launcher theme decides.
 */
data class PopupProfile(
    val style: String,
    val radiusDp: Int,
    val opacityPct: Int,
    val backgroundColor: Int?,
    val textColor: Int?,
) {
    /** Text color to draw on this menu, falling back to a readable contrast for a custom background. */
    val effectiveTextColor: Int?
        get() = textColor ?: backgroundColor?.let { bg ->
            if (androidx.core.graphics.ColorUtils.calculateLuminance(bg) > 0.5) Color.BLACK else Color.WHITE
        }
}

object PopupMenuStyle {

    @JvmStatic
    fun resolve(context: Context, kind: PopupKind): PopupProfile {
        val prefs = PreferenceManager.getInstance(context)
        val prefs2 = PreferenceManager2.getInstance(context)
        val linked = prefs.popupMenuLinked.get()
        val profile = if (linked) prefs.popupSharedProfile else prefs.popupProfiles.getValue(kind.id)
        val background = if (linked) {
            prefs2.popupSharedBackgroundColor
        } else {
            when (kind) {
                PopupKind.HOME_MENU -> prefs2.popupHomeMenuBackgroundColor
                PopupKind.APP_DRAWER -> prefs2.popupAppDrawerBackgroundColor
                PopupKind.APP_HOME -> prefs2.popupAppHomeBackgroundColor
                PopupKind.APP_DOCK -> prefs2.popupAppDockBackgroundColor
            }
        }
        val text = if (linked) {
            prefs2.popupSharedTextColor
        } else {
            when (kind) {
                PopupKind.HOME_MENU -> prefs2.popupHomeMenuTextColor
                PopupKind.APP_DRAWER -> prefs2.popupAppDrawerTextColor
                PopupKind.APP_HOME -> prefs2.popupAppHomeTextColor
                PopupKind.APP_DOCK -> prefs2.popupAppDockTextColor
            }
        }
        return PopupProfile(
            style = profile.style.get(),
            radiusDp = profile.radiusDp.get(),
            opacityPct = profile.opacityPct.get(),
            backgroundColor = background.firstCached(prefs2).toColorInt(context),
            textColor = text.firstCached(prefs2).toColorInt(context),
        )
    }

    private fun ColorOption.toColorInt(context: Context): Int? = if (this is ColorOption.Default) null else colorPreferenceEntry.lightColor.invoke(context)
}
