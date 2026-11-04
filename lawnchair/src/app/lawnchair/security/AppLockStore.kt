package app.lawnchair.security

import android.content.Context

/** Local package allow-list for launcher-triggered app-lock prompts. */
object AppLockStore {
    private const val PREFS = "solina_app_lock"
    private const val KEY_LOCKED_PACKAGES = "locked_packages"

    fun lockedPackages(context: Context): Set<String> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(KEY_LOCKED_PACKAGES, emptySet())
        .orEmpty()
        .toSet()

    fun isLocked(context: Context, packageName: String): Boolean = packageName in lockedPackages(context)

    fun replaceLockedPackages(context: Context, packages: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        check(prefs.edit().putStringSet(KEY_LOCKED_PACKAGES, packages.toSet()).commit()) {
            "Unable to commit app lock preferences"
        }
    }

    fun setLocked(context: Context, packageName: String, locked: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val packages = prefs.getStringSet(KEY_LOCKED_PACKAGES, emptySet()).orEmpty().toMutableSet()
        if (locked) packages.add(packageName) else packages.remove(packageName)
        prefs.edit().putStringSet(KEY_LOCKED_PACKAGES, packages).apply()
    }
}
