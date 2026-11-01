package app.lawnchair.util

import android.content.Context
import app.lawnchair.flowerpot.Flowerpot
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.util.ApplicationInfoWrapper
import java.util.Locale

/**
 * Categorizes apps into stable, alphabetically ordered System, Google and Flowerpot groups.
 *
 * Google apps are checked first because some of them are also system applications. Flowerpot
 * remains responsible for categorizing all other applications.
 */
fun categorizeAppsWithSystemAndGoogle(
    apps: List<AppInfo>,
    context: Context,
): Map<String, List<AppInfo>> {
    val systemApps = mutableListOf<AppInfo>()
    val googleApps = mutableListOf<AppInfo>()
    val otherApps = mutableListOf<AppInfo>()

    apps.forEach { app ->
        val packageName = app.targetPackage ?: return@forEach
        val intent = app.intent

        when {
            packageName.startsWith("com.google.", ignoreCase = true) -> googleApps.add(app)
            intent != null && ApplicationInfoWrapper(context, intent).isSystem() -> systemApps.add(app)
            else -> otherApps.add(app)
        }
    }

    val categorizedApps = Flowerpot.Manager.getInstance(context).categorizeApps(otherApps)
    val result = linkedMapOf<String, List<AppInfo>>()

    fun addCategory(name: String, categoryApps: List<AppInfo>) {
        if (categoryApps.isNotEmpty()) {
            result[name] = categoryApps.sortedWith(
                compareBy<AppInfo> { it.title.toString().lowercase(Locale.ROOT) }
                    .thenBy { it.targetPackage.orEmpty() }
                    .thenBy { it.targetComponent?.className.orEmpty() },
            )
        }
    }

    addCategory("System Apps", systemApps)
    addCategory("Google Apps", googleApps)

    // Flowerpot category order is made deterministic so Drawer positions do not jump between
    // refreshes when the underlying map implementation changes.
    categorizedApps.entries
        .sortedWith(compareBy<Map.Entry<String, List<AppInfo>>> { it.key.lowercase(Locale.ROOT) }.thenBy { it.key })
        .forEach { (name, categoryApps) -> addCategory(name, categoryApps) }

    return result
}
