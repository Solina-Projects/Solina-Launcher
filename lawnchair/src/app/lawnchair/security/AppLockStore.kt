package app.lawnchair.security

import android.content.Context
import java.security.MessageDigest

object AppLockStore {
    private const val PREFS = "solina_app_lock"
    private const val KEY_LOCKED_PACKAGES = "locked_packages"
    private const val KEY_CUSTOM_PIN_HASH = "custom_pin_hash"

    fun lockedPackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_LOCKED_PACKAGES, emptySet()).orEmpty().toSet()
    }

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

    fun hasCustomPin(context: Context): Boolean = !getPinHash(context).isNullOrEmpty()

    fun getPinHash(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CUSTOM_PIN_HASH, null)
    }

    fun setPinHash(context: Context, hash: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (hash.isNullOrEmpty()) {
            prefs.edit().remove(KEY_CUSTOM_PIN_HASH).apply()
        } else {
            prefs.edit().putString(KEY_CUSTOM_PIN_HASH, hash).apply()
        }
    }

    fun setCustomPin(context: Context, pin: String) {
        val cleaned = pin.filter { it.isDigit() }
        require(cleaned.length == 4) { "PIN must be 4 digits" }
        setPinHash(context, sha256(cleaned))
    }

    fun clearCustomPin(context: Context) {
        setPinHash(context, null)
    }

    fun verifyCustomPin(context: Context, pin: String): Boolean {
        val stored = getPinHash(context) ?: return false
        return stored == sha256(pin.filter { it.isDigit() })
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
