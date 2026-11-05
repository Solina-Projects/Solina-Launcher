package app.lawnchair.ui.preferences.destinations

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.security.AppLockStore
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.controls.ListPreference
import app.lawnchair.ui.preferences.components.controls.ListPreferenceEntry
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayoutLazyColumn
import com.android.launcher3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class LockableApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
)

@Composable
fun AppLockPreferences(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = preferenceManager()
    var apps by remember { mutableStateOf(emptyList<LockableApp>()) }
    var lockedPackages by remember { mutableStateOf(AppLockStore.lockedPackages(context)) }
    var hasCustomPin by remember { mutableStateOf(AppLockStore.hasCustomPin(context)) }
    var showPinDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showOnlyLocked by remember { mutableStateOf(false) }

    val lockStyleEntries = remember {
        listOf(
            ListPreferenceEntry("glass_modern") { stringResource(R.string.app_lock_style_glass_modern) },
            ListPreferenceEntry("neon_cyber") { stringResource(R.string.app_lock_style_neon_cyber) },
            ListPreferenceEntry("minimal_clean") { stringResource(R.string.app_lock_style_minimal_clean) },
            ListPreferenceEntry("system_direct") { stringResource(R.string.app_lock_style_system_direct) },
        )
    }

    LaunchedEffect(context) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .mapNotNull { info ->
                    if (
                        info.packageName == context.packageName ||
                        pm.getLaunchIntentForPackage(info.packageName) == null
                    ) {
                        null
                    } else {
                        val iconDrawable = try {
                            pm.getApplicationIcon(info)
                        } catch (_: Throwable) {
                            null
                        }
                        LockableApp(
                            packageName = info.packageName,
                            label = pm.getApplicationLabel(info).toString(),
                            icon = iconDrawable,
                        )
                    }
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    val filteredApps = remember(apps, searchQuery, showOnlyLocked, lockedPackages) {
        apps.filter { app ->
            val matchesLock = !showOnlyLocked || app.packageName in lockedPackages
            val matchesQuery = searchQuery.isBlank() ||
                app.label.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)
            matchesLock && matchesQuery
        }
    }

    if (showPinDialog) {
        CustomPinSetupDialog(
            hasExistingPin = hasCustomPin,
            onSavePin = { newPin ->
                AppLockStore.setCustomPin(context, newPin)
                hasCustomPin = true
                showPinDialog = false
                Toast.makeText(context, R.string.app_lock_pin_saved_toast, Toast.LENGTH_SHORT).show()
            },
            onClearPin = {
                AppLockStore.clearCustomPin(context)
                hasCustomPin = false
                showPinDialog = false
                Toast.makeText(context, R.string.app_lock_pin_cleared_toast, Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showPinDialog = false },
        )
    }

    PreferenceLayoutLazyColumn(
        label = stringResource(R.string.app_lock_title),
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        item {
            PreferenceGroup(heading = stringResource(R.string.style)) {
                ListPreference(
                    adapter = prefs.appLockScreenStyle.getAdapter(),
                    entries = lockStyleEntries,
                    label = stringResource(R.string.app_lock_screen_style_label),
                )
                SwitchPreference(
                    adapter = prefs.appLockAutoBiometric.getAdapter(),
                    label = stringResource(R.string.app_lock_auto_biometric_label),
                    description = stringResource(R.string.app_lock_auto_biometric_summary),
                )
                ClickablePreference(
                    label = stringResource(R.string.app_lock_custom_pin_label),
                    subtitle = if (hasCustomPin) {
                        stringResource(R.string.app_lock_custom_pin_active)
                    } else {
                        stringResource(R.string.app_lock_custom_pin_not_set)
                    },
                    onClick = { showPinDialog = true },
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    placeholder = { Text(stringResource(R.string.app_lock_search_hint)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Clear,
                                    contentDescription = null,
                                )
                            }
                        }
                    },
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = !showOnlyLocked,
                        onClick = { showOnlyLocked = false },
                        label = { Text(stringResource(R.string.all_apps_category_all)) },
                    )
                    FilterChip(
                        selected = showOnlyLocked,
                        onClick = { showOnlyLocked = true },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        label = {
                            Text(
                                "${stringResource(R.string.app_lock_filter_locked)} (${lockedPackages.size})",
                            )
                        },
                    )
                }
            }
        }

        if (filteredApps.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.app_lock_empty),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        } else {
            items(filteredApps, key = { it.packageName }) { app ->
                val isLocked = app.packageName in lockedPackages
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable {
                            val next = !isLocked
                            AppLockStore.setLocked(context, app.packageName, next)
                            lockedPackages = if (next) {
                                lockedPackages + app.packageName
                            } else {
                                lockedPackages - app.packageName
                            }
                        },
                    shape = RoundedCornerShape(18.dp),
                    color = if (isLocked) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (app.icon != null) {
                            val bmp = remember(app.packageName) {
                                app.icon.toBitmap(width = 96, height = 96).asImageBitmap()
                            }
                            Image(
                                bitmap = bmp,
                                contentDescription = app.label,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = app.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isLocked) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Switch(
                            checked = isLocked,
                            onCheckedChange = { checked ->
                                AppLockStore.setLocked(context, app.packageName, checked)
                                lockedPackages = if (checked) {
                                    lockedPackages + app.packageName
                                } else {
                                    lockedPackages - app.packageName
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomPinSetupDialog(
    hasExistingPin: Boolean,
    onSavePin: (String) -> Unit,
    onClearPin: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pinValue by remember { mutableStateOf("") }
    val isValid = pinValue.length == 4 && pinValue.all { it.isDigit() }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Password,
                contentDescription = null,
            )
        },
        title = {
            Text(text = stringResource(R.string.app_lock_custom_pin_label))
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.app_lock_custom_pin_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = pinValue,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(4)
                        pinValue = digits
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    label = { Text(stringResource(R.string.app_lock_pin_input_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSavePin(pinValue) },
                enabled = isValid,
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            Row {
                if (hasExistingPin) {
                    TextButton(onClick = onClearPin) {
                        Text(
                            text = stringResource(R.string.app_lock_clear_pin_btn),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}
