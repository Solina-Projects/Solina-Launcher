/*
 * Copyright 2026, Solina Projects
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.android.launcher3.R
import java.io.IOException

/**
 * Applies Solina's bundled wallpaper only on a fresh installation.
 * Existing users' chosen home and lock-screen wallpapers are never replaced on app updates.
 */
internal object DefaultWallpaperInitializer {
    private const val TAG = "SolinaDefaultWallpaper"
    private const val PREFS_NAME = "solina_default_wallpaper"
    private const val KEY_APPLIED = "default_wallpaper_applied"

    fun applyOnFreshInstall(context: Context) {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        if (packageInfo.firstInstallTime != packageInfo.lastUpdateTime) return

        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.getBoolean(KEY_APPLIED, false)) return

        try {
            val wallpaperManager = WallpaperManager.getInstance(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                wallpaperManager.setResource(
                    R.drawable.solina_default_wallpaper,
                    WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
                )
            } else {
                wallpaperManager.setResource(R.drawable.solina_default_wallpaper)
            }
            preferences.edit().putBoolean(KEY_APPLIED, true).apply()
        } catch (exception: IOException) {
            Log.e(TAG, "Unable to apply the bundled default wallpaper", exception)
        } catch (exception: SecurityException) {
            Log.e(TAG, "Wallpaper permission was denied", exception)
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Unable to initialize the bundled default wallpaper", exception)
        }
    }
}
