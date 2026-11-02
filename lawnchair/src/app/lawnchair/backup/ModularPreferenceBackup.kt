package app.lawnchair.backup

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores preference values by feature instead of copying the complete preference stores.
 *
 * Unknown/future keys are deliberately retained in the "other" module so a new preference
 * cannot silently disappear from a backup.
 */
internal object ModularPreferenceBackup {
    const val SCHEMA_VERSION = 1

    val modules = listOf(
        "home_screen", "dock", "app_drawer", "folders", "icons", "theme",
        "typography", "wallpaper", "widgets", "gestures", "search", "smartspace",
        "taskbar", "animations", "notifications", "general", "app_overrides", "other",
    )

    fun moduleFor(key: String): String {
        val k = key.lowercase()
        return when {
            k.contains("hotseat") || k.startsWith("dock_") || k.contains("dock") -> "dock"
            k.contains("drawer") || k.startsWith("all_apps") || k.contains("prediction") ||
                k.contains("hidden_apps") -> "app_drawer"
            k.contains("folder") -> "folders"
            k.contains("icon") || k.contains("themed_icons") || k.contains("adaptive") -> "icons"
            k.contains("font") || k.contains("typography") -> "typography"
            k.contains("wallpaper") -> "wallpaper"
            k.contains("widget") -> "widgets"
            k.contains("gesture") || k.contains("sleep_mode") -> "gestures"
            k.contains("search") || k.contains("qsb") || k.contains("web_suggestion") -> "search"
            k.contains("smartspace") || k.contains("smart_space") || k.contains("feed") -> "smartspace"
            k.contains("taskbar") || k.contains("quickstep") -> "taskbar"
            k.contains("animation") || k.contains("transition") -> "animations"
            k.contains("notification") || k.contains("dot") -> "notifications"
            k.contains("color") || k.contains("theme") || k.contains("accent") ||
                k.contains("background") || k.contains("opacity") || k.contains("blur") ||
                k.contains("corner_radius") -> "theme"
            k.contains("workspace") || k.contains("home_screen") || k.contains("home_icon") ||
                k.contains("page_indicator") || k.contains("grid") || k.contains("rotation") ||
                k.contains("lock_home") -> "home_screen"
            k.contains("appname") || k.contains("app_override") || k.contains("componentkey") -> "app_overrides"
            k.startsWith("pref_") || k.startsWith("prefs_") || k in setOf(
                "allowrotation", "auto_launch_root", "infinite_scrolling", "vibration_feedback_level",
            ) -> "general"
            else -> "other"
        }
    }

    suspend fun export(context: Context, selectedModules: Set<String>): ByteArray {
        val root = JSONObject().put("schema", SCHEMA_VERSION)
        val manager = PreferenceManager.getInstance(context)
        val shared = JSONObject()
        manager.sp.all.forEach { (key, value) ->
            if (value != null && moduleFor(key) in selectedModules) shared.put(key, encode(value))
        }
        root.put("shared_preferences", shared)

        val dataStore = PreferenceManager2.getInstance(context).preferencesDataStore
        val snapshot = dataStore.data.first()
        val data = JSONObject()
        snapshot.asMap().forEach { (key, value) ->
            if (moduleFor(key.name) in selectedModules) data.put(key.name, encode(value))
        }
        root.put("datastore_preferences", data)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    suspend fun restore(context: Context, bytes: ByteArray, selectedModules: Set<String>) {
        require(bytes.size <= MAX_SNAPSHOT_BYTES) { "Preference snapshot is too large" }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.optInt("schema", -1) in 1..SCHEMA_VERSION) { "Unsupported preference snapshot" }

        val shared = root.optJSONObject("shared_preferences") ?: JSONObject()
        val editor = PreferenceManager.getInstance(context).sp.edit()
        shared.keys().forEach { key ->
            if (moduleFor(key) in selectedModules) decode(shared.getJSONObject(key))?.let { value ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }
        editor.apply()

        val data = root.optJSONObject("datastore_preferences") ?: JSONObject()
        val store = PreferenceManager2.getInstance(context).preferencesDataStore
        store.edit { current ->
            data.keys().forEach { name ->
                if (moduleFor(name) !in selectedModules) return@forEach
                val value = decode(data.getJSONObject(name)) ?: return@forEach
                when (value) {
                    is Boolean -> current[booleanPreferencesKey(name)] = value
                    is Int -> current[intPreferencesKey(name)] = value
                    is Long -> current[longPreferencesKey(name)] = value
                    is Float -> current[floatPreferencesKey(name)] = value
                    is Double -> current[doublePreferencesKey(name)] = value
                    is String -> current[stringPreferencesKey(name)] = value
                    is Set<*> -> current[stringSetPreferencesKey(name)] = value.filterIsInstance<String>().toSet()
                }
            }
        }
    }

    private fun encode(value: Any): JSONObject = when (value) {
        is Boolean -> JSONObject().put("type", "boolean").put("value", value)
        is Int -> JSONObject().put("type", "int").put("value", value)
        is Long -> JSONObject().put("type", "long").put("value", value)
        is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
        is Double -> JSONObject().put("type", "double").put("value", value)
        is String -> JSONObject().put("type", "string").put("value", value)
        is Set<*> -> JSONObject().put("type", "string_set").put("value", JSONArray(value.toList()))
        else -> JSONObject().put("type", "unsupported")
    }

    private fun decode(encoded: JSONObject): Any? {
        val value = encoded.opt("value")
        return when (encoded.optString("type")) {
            "boolean" -> value as? Boolean
            "int" -> (value as? Number)?.toInt()
            "long" -> (value as? Number)?.toLong()
            "float" -> (value as? Number)?.toFloat()
            "double" -> (value as? Number)?.toDouble()
            "string" -> value as? String
            "string_set" -> (value as? JSONArray)?.let { array ->
                buildSet { for (i in 0 until array.length()) array.optString(i).takeIf(String::isNotEmpty)?.let(::add) }
            }
            else -> null
        }
    }

    private const val MAX_SNAPSHOT_BYTES = 16 * 1024 * 1024
}
