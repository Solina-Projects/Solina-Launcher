package app.lawnchair.ui.preferences.destinations

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lawnchair.security.AppLockStore
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import com.android.launcher3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class LockableApp(val packageName: String, val label: String)

@Composable
fun AppLockPreferences(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(emptyList<LockableApp>()) }
    var lockedPackages by remember { mutableStateOf(AppLockStore.lockedPackages(context)) }

    LaunchedEffect(context) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .mapNotNull { info ->
                    if (info.packageName == context.packageName || pm.getLaunchIntentForPackage(info.packageName) == null) {
                        null
                    } else {
                        LockableApp(info.packageName, pm.getApplicationLabel(info).toString())
                    }
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    PreferenceLayout(
        label = stringResource(R.string.app_lock_title),
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.app_lock_description),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        )
        Text(
            text = stringResource(R.string.app_lock_apps_heading),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (apps.isEmpty()) {
            Text(
                text = stringResource(R.string.app_lock_empty),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        } else {
            LazyColumn {
                items(apps, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(text = app.label, modifier = Modifier.weight(1f))
                        Switch(
                            checked = app.packageName in lockedPackages,
                            onCheckedChange = { checked ->
                                AppLockStore.setLocked(context, app.packageName, checked)
                                lockedPackages = if (checked) lockedPackages + app.packageName else lockedPackages - app.packageName
                            },
                        )
                    }
                }
            }
        }
    }
}
