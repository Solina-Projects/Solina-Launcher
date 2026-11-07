package app.lawnchair.ui.preferences.destinations

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TipsAndUpdates
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import app.lawnchair.LawnchairApp
import app.lawnchair.SolinaLauncher
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.observeAsState
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.preferences2.firstCached
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.security.AppLockStore
import app.lawnchair.ui.OverflowMenuGrouped
import app.lawnchair.ui.preferences.components.AnnouncementPreference
import app.lawnchair.ui.preferences.components.controls.PreferenceCategory
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.controls.WarningPreference
import app.lawnchair.ui.preferences.components.layout.ClickableIcon
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.components.layout.ProvideDescriptionTextStyle
import app.lawnchair.ui.preferences.data.liveinfo.SyncLiveInformation
import app.lawnchair.ui.preferences.navigation.About
import app.lawnchair.ui.preferences.navigation.AppDrawer
import app.lawnchair.ui.preferences.navigation.AppLock
import app.lawnchair.ui.preferences.navigation.BackupAndRestore
import app.lawnchair.ui.preferences.navigation.DebugMenu
import app.lawnchair.ui.preferences.navigation.Dock
import app.lawnchair.ui.preferences.navigation.ExperimentalFeatures
import app.lawnchair.ui.preferences.navigation.Folders
import app.lawnchair.ui.preferences.navigation.General
import app.lawnchair.ui.preferences.navigation.Gestures
import app.lawnchair.ui.preferences.navigation.HomeScreen
import app.lawnchair.ui.preferences.navigation.Predictions
import app.lawnchair.ui.preferences.navigation.PreferenceRootRoute
import app.lawnchair.ui.preferences.navigation.Quickstep
import app.lawnchair.ui.preferences.navigation.Search
import app.lawnchair.ui.preferences.navigation.Smartspace
import app.lawnchair.ui.util.addIf
import app.lawnchair.util.isDefaultLauncher
import app.lawnchair.util.restartLauncher
import com.android.launcher3.BuildConfig
import com.android.launcher3.R
import com.android.launcher3.util.MSDLPlayerWrapper
import com.google.android.msdl.data.model.MSDLToken

private enum class DashboardSectionGroup {
    WORKSPACE,
    APPEARANCE,
    SMART_SEARCH,
    SECURITY_SYSTEM,
}

private data class DashboardCategoryItem(
    val label: String,
    val description: String,
    @DrawableRes val iconResource: Int,
    val route: PreferenceRootRoute,
    val isSelected: Boolean,
    val accentColor: Color,
    val group: DashboardSectionGroup,
    val visible: Boolean = true,
)

@Composable
fun PreferencesDashboard(
    currentRoute: PreferenceRootRoute,
    onNavigate: (PreferenceRootRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    SyncLiveInformation()

    val prefs = preferenceManager()
    val prefs2 = preferenceManager2()

    val dashboardLayout by prefs.settingsDashboardLayout.observeAsState()
    val showQuickStatusHeader by prefs.settingsShowQuickStatusHeader.observeAsState()
    val showSearchBar by prefs.settingsSearchBarEnabled.observeAsState()
    val colorfulIcons by prefs.settingsColorfulIcons.observeAsState()
    val launcherTheme by prefs.launcherTheme.observeAsState()
    val launcherThemeAdapter = prefs.launcherTheme.getAdapter()
    val deckLayout = prefs2.deckLayout.getAdapter()
    val isSmartspaceEnabled = prefs2.enableSmartspace.firstCached()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showCustomizerPanel by rememberSaveable { mutableStateOf(false) }

    val aboutDescription = if (prefs.hideVersionInfo.get()) {
        prefs.pseudonymVersion.get()
    } else {
        "${context.getString(R.string.derived_app_name)} ${BuildConfig.MAJOR_VERSION} • Solina"
    }

    val allItems = listOf(
        DashboardCategoryItem(
            label = stringResource(R.string.general_label),
            description = stringResource(R.string.general_description),
            iconResource = R.drawable.ic_general,
            route = General,
            isSelected = currentRoute is General,
            accentColor = Color(0xFF4F8EF7),
            group = DashboardSectionGroup.APPEARANCE,
        ),
        DashboardCategoryItem(
            label = stringResource(R.string.home_screen_label),
            description = stringResource(R.string.home_screen_description),
            iconResource = R.drawable.ic_home_screen,
            route = HomeScreen,
            isSelected = currentRoute is HomeScreen,
            accentColor = Color(0xFF26A69A),
            group = DashboardSectionGroup.WORKSPACE,
        ),
        DashboardCategoryItem(
            label = stringResource(id = R.string.smartspace_widget),
            description = stringResource(R.string.smartspace_widget_description),
            iconResource = if (isSmartspaceEnabled) R.drawable.ic_smartspace else R.drawable.ic_smartspace_off,
            route = Smartspace,
            isSelected = currentRoute is Smartspace,
            accentColor = Color(0xFFFFA726),
            group = DashboardSectionGroup.SMART_SEARCH,
        ),
        DashboardCategoryItem(
            label = stringResource(R.string.dock_label),
            description = stringResource(R.string.dock_description),
            iconResource = R.drawable.ic_dock,
            route = Dock,
            isSelected = currentRoute is Dock,
            accentColor = Color(0xFF7E57C2),
            group = DashboardSectionGroup.WORKSPACE,
        ),
        DashboardCategoryItem(
            label = stringResource(R.string.app_drawer_label),
            description = stringResource(R.string.app_drawer_description),
            iconResource = R.drawable.ic_apps,
            route = AppDrawer,
            isSelected = currentRoute is AppDrawer,
            accentColor = Color(0xFF42A5F5),
            group = DashboardSectionGroup.APPEARANCE,
            visible = !deckLayout.state.value,
        ),
        DashboardCategoryItem(
            label = stringResource(R.string.suggestion_pref_screen_title),
            description = stringResource(R.string.suggestion_dashboard_description),
            iconResource = R.drawable.ic_new_releases,
            route = Predictions,
            isSelected = currentRoute is Predictions,
            accentColor = Color(0xFFAB47BC),
            group = DashboardSectionGroup.SMART_SEARCH,
        ),
        DashboardCategoryItem(
            label = stringResource(R.string.search_bar_label),
            description = stringResource(R.string.drawer_search_description),
            iconResource = R.drawable.ic_search,
            route = Search(),
            isSelected = currentRoute is Search,
            accentColor = Color(0xFFEC407A),
            group = DashboardSectionGroup.SMART_SEARCH,
        ),
        DashboardCategoryItem(
            label = stringResource(R.string.folders_label),
            description = stringResource(R.string.folders_description),
            iconResource = R.drawable.ic_folder,
            route = Folders,
            isSelected = currentRoute is Folders,
            accentColor = Color(0xFFFF7043),
            group = DashboardSectionGroup.WORKSPACE,
        ),
        DashboardCategoryItem(
            label = stringResource(id = R.string.gestures_label),
            description = stringResource(R.string.gestures_description),
            iconResource = R.drawable.ic_gestures,
            route = Gestures,
            isSelected = currentRoute is Gestures,
            accentColor = Color(0xFF66BB6A),
            group = DashboardSectionGroup.SMART_SEARCH,
        ),
        DashboardCategoryItem(
            label = stringResource(R.string.app_lock_title),
            description = stringResource(R.string.app_lock_description),
            iconResource = R.drawable.ic_lock,
            route = AppLock,
            isSelected = currentRoute is AppLock,
            accentColor = Color(0xFFEF5350),
            group = DashboardSectionGroup.SECURITY_SYSTEM,
        ),
        DashboardCategoryItem(
            label = stringResource(id = R.string.quickstep_label),
            description = stringResource(id = R.string.quickstep_description),
            iconResource = R.drawable.ic_quickstep,
            route = Quickstep,
            isSelected = currentRoute is Quickstep,
            accentColor = Color(0xFF5C6BC0),
            group = DashboardSectionGroup.SMART_SEARCH,
            visible = LawnchairApp.isQuickstepBuild || LawnchairApp.isRecentsEnabled || BuildConfig.DEBUG,
        ),
        DashboardCategoryItem(
            label = stringResource(R.string.backup_and_restore_label),
            description = stringResource(R.string.backup_and_restore_description),
            iconResource = R.drawable.backup_restore,
            route = BackupAndRestore,
            isSelected = currentRoute is BackupAndRestore,
            accentColor = Color(0xFF29B6F6),
            group = DashboardSectionGroup.SECURITY_SYSTEM,
        ),
        DashboardCategoryItem(
            label = stringResource(R.string.about_label),
            description = aboutDescription,
            iconResource = R.drawable.ic_about,
            route = About,
            isSelected = currentRoute is About,
            accentColor = Color(0xFF8D6E63),
            group = DashboardSectionGroup.SECURITY_SYSTEM,
        ),
    ).filter { it.visible }

    val filteredItems = remember(allItems, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            allItems
        } else {
            allItems.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.description.contains(query, ignoreCase = true)
            }
        }
    }

    PreferenceLayout(
        label = stringResource(id = R.string.settings),
        modifier = modifier,
        verticalArrangement = Arrangement.Top,
        backArrowVisible = false,
        actions = {
            ClickableIcon(
                imageVector = if (launcherTheme == "dark") {
                    Icons.Rounded.LightMode
                } else {
                    Icons.Rounded.DarkMode
                },
                onClick = {
                    launcherThemeAdapter.onChange(if (launcherTheme == "dark") "light" else "dark")
                },
            )
            PreferencesOverflowMenu(currentRoute = currentRoute, onNavigate = onNavigate)
        },
    ) {
        AnnouncementPreference()

        DashboardQuickSettingsButton(
            expanded = showCustomizerPanel,
            onClick = { showCustomizerPanel = !showCustomizerPanel },
        )
        Spacer(modifier = Modifier.height(10.dp))

        if (BuildConfig.APPLICATION_ID.contains("nightly") || BuildConfig.DEBUG) {
            PreferencesDebugWarning()
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (!context.isDefaultLauncher()) {
            PreferencesSetDefaultLauncherWarning()
            Spacer(modifier = Modifier.height(8.dp))
        }

        AnimatedVisibility(visible = showCustomizerPanel) {
            Column {
                SettingsStyleCustomizerCard()
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        AnimatedVisibility(visible = showQuickStatusHeader && searchQuery.isBlank()) {
            Column {
                DashboardQuickStatusHeader(onNavigate = onNavigate)
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        AnimatedVisibility(visible = showSearchBar) {
            Column {
                DashboardSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        if (filteredItems.isEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_search_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            when (dashboardLayout) {
                "bento" -> DashboardBentoGrid(
                    items = filteredItems,
                    colorfulIcons = colorfulIcons,
                    onNavigate = onNavigate,
                )

                "grouped" -> DashboardGroupedCards(
                    items = filteredItems,
                    onNavigate = onNavigate,
                )

                else -> PreferenceGroup {
                    filteredItems.forEach { item ->
                        PreferenceCategory(
                            label = item.label,
                            description = item.description,
                            iconResource = item.iconResource,
                            onNavigate = { onNavigate(item.route) },
                            isSelected = item.isSelected,
                            accentColor = item.accentColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsStyleCustomizerCard(
    modifier: Modifier = Modifier,
) {
    val prefs = preferenceManager()
    val currentLayout by prefs.settingsDashboardLayout.observeAsState()
    val layoutOptions = listOf(
        "bento" to stringResource(R.string.settings_layout_bento),
        "grouped" to stringResource(R.string.settings_layout_grouped),
        "classic" to stringResource(R.string.settings_layout_classic),
    )

    PreferenceGroup(
        heading = stringResource(R.string.settings_customize_sheet_title),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            layoutOptions.forEach { (key, title) ->
                val selected = currentLayout == key
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { prefs.settingsDashboardLayout.set(key) },
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        SwitchPreference(
            adapter = prefs.settingsShowQuickStatusHeader.getAdapter(),
            label = stringResource(R.string.settings_show_status_header_label),
            description = stringResource(R.string.settings_show_status_header_desc),
        )
        SwitchPreference(
            adapter = prefs.settingsSearchBarEnabled.getAdapter(),
            label = stringResource(R.string.settings_search_bar_label),
            description = stringResource(R.string.settings_search_bar_desc),
        )
        SwitchPreference(
            adapter = prefs.settingsColorfulIcons.getAdapter(),
            label = stringResource(R.string.settings_colorful_icons_label),
            description = stringResource(R.string.settings_colorful_icons_desc),
        )
        SwitchPreference(
            adapter = prefs.settingsStepperSliders.getAdapter(),
            label = stringResource(R.string.settings_stepper_sliders_label),
            description = stringResource(R.string.settings_stepper_sliders_desc),
        )
    }
}

@Composable
private fun DashboardQuickStatusHeader(
    onNavigate: (PreferenceRootRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = preferenceManager()
    val workspaceCols by prefs.workspaceColumns.getAdapter()
    val workspaceRows by prefs.workspaceRows.getAdapter()
    val dockRows by prefs.hotseatRows.getAdapter()
    val dockCols by prefs.hotseatColumns.getAdapter()
    val lockedPackages = remember(context) {
        AppLockStore.lockedPackages(context)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuickStatusChip(
                iconRes = R.drawable.ic_home_screen,
                text = stringResource(R.string.settings_quick_status_grid, workspaceCols, workspaceRows),
                accentColor = Color(0xFF26A69A),
                onClick = { onNavigate(HomeScreen) },
                modifier = Modifier.weight(1f),
            )
            QuickStatusChip(
                iconRes = R.drawable.ic_dock,
                text = stringResource(R.string.settings_quick_status_dock, dockRows, dockCols),
                accentColor = Color(0xFF7E57C2),
                onClick = { onNavigate(Dock) },
                modifier = Modifier.weight(1f),
            )
            QuickStatusChip(
                iconRes = R.drawable.ic_lock,
                text = stringResource(R.string.settings_quick_status_locked_apps, lockedPackages.size),
                accentColor = Color(0xFFEF5350),
                onClick = { onNavigate(AppLock) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun QuickStatusChip(
    @DrawableRes iconRes: Int,
    text: String,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = accentColor.copy(alpha = 0.14f),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DashboardQuickSettingsButton(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 168.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_quick_access),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DashboardSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_search_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onQueryChange("") },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardBentoGrid(
    items: List<DashboardCategoryItem>,
    colorfulIcons: Boolean,
    onNavigate: (PreferenceRootRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mMSDLPlayerWrapper = MSDLPlayerWrapper.INSTANCE.get(context)
    val rows = remember(items) { items.chunked(2) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowItems.forEach { item ->
                    val cardAccent = if (colorfulIcons) {
                        item.accentColor
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = if (item.isSelected) {
                            cardAccent.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(22.dp))
                            .border(
                                width = if (item.isSelected) 1.5.dp else 1.dp,
                                color = if (item.isSelected) {
                                    cardAccent.copy(alpha = 0.6f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                },
                                shape = RoundedCornerShape(22.dp),
                            )
                            .clickable {
                                mMSDLPlayerWrapper.playToken(MSDLToken.TAP_LOW_EMPHASIS)
                                onNavigate(item.route)
                            },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(
                                        if (colorfulIcons) {
                                            cardAccent.copy(alpha = 0.16f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                    ),
                            ) {
                                Icon(
                                    painter = painterResource(id = item.iconResource),
                                    contentDescription = null,
                                    tint = if (colorfulIcons) {
                                        cardAccent
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                minLines = 2,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardGroupedCards(
    items: List<DashboardCategoryItem>,
    onNavigate: (PreferenceRootRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = listOf(
        DashboardSectionGroup.WORKSPACE to stringResource(R.string.settings_group_workspace),
        DashboardSectionGroup.APPEARANCE to stringResource(R.string.settings_group_appearance),
        DashboardSectionGroup.SMART_SEARCH to stringResource(R.string.settings_group_smart_search),
        DashboardSectionGroup.SECURITY_SYSTEM to stringResource(R.string.settings_group_security_system),
    )

    Column(modifier = modifier) {
        groups.forEach { (sectionGroup, heading) ->
            val groupItems = items.filter { it.group == sectionGroup }
            if (groupItems.isNotEmpty()) {
                PreferenceGroup(heading = heading) {
                    groupItems.forEach { item ->
                        PreferenceCategory(
                            label = item.label,
                            description = item.description,
                            iconResource = item.iconResource,
                            onNavigate = { onNavigate(item.route) },
                            isSelected = item.isSelected,
                            accentColor = item.accentColor,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RowScope.PreferencesOverflowMenu(
    currentRoute: PreferenceRootRoute,
    onNavigate: (PreferenceRootRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enableDebug by preferenceManager().enableDebugMenu.observeAsState()
    val highlightColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
    val highlightShape = MaterialTheme.shapes.large
    if (enableDebug) {
        ClickableIcon(
            imageVector = Icons.Rounded.Build,
            onClick = { onNavigate(DebugMenu) },
            modifier = Modifier.addIf(currentRoute == DebugMenu) {
                Modifier
                    .clip(highlightShape)
                    .background(highlightColor)
            },
        )
    }
    val context = LocalContext.current
    OverflowMenuGrouped(
        modifier = modifier.addIf(
            listOf(ExperimentalFeatures).any {
                currentRoute == it
            },
        ) {
            Modifier
                .clip(highlightShape)
                .background(highlightColor)
        },
    ) {
        DropdownMenuGroup(
            shapes = MenuDefaults.groupShape(0, 1),
        ) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_about),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    openAppInfo(context)
                    hideMenu()
                },
                text = {
                    Text(text = stringResource(id = R.string.app_info_drop_target_label))
                },
            )
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    restartLauncher(context)
                    hideMenu()
                },
                text = {
                    Text(text = stringResource(id = R.string.debug_restart_launcher))
                },
            )
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Science,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    onNavigate(ExperimentalFeatures)
                    hideMenu()
                },
                text = {
                    Text(text = stringResource(id = R.string.experimental_features_label))
                },
            )
        }
        Spacer(Modifier.height(MenuDefaults.GroupSpacing))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferencesDebugWarning(
    modifier: Modifier = Modifier,
) {
    WarningPreference(
        // Don't move to strings.xml, no need to translate this warning
        text = "You are using a development build, which may contain bugs and broken features. Use at your own risk!",
        modifier = modifier.padding(horizontal = 16.dp),
        standalone = true,
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    )
}

@Composable
fun PreferencesSetDefaultLauncherWarning(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mMSDLPlayerWrapper = MSDLPlayerWrapper.INSTANCE.get(context)

    Surface(
        modifier = modifier.padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        PreferenceTemplate(
            modifier = Modifier,
            onClick = {
                mMSDLPlayerWrapper.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
                Intent(Settings.ACTION_HOME_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .let { context.startActivity(it) }
                (context as? Activity)?.finish()
            },
            title = {
                ProvideDescriptionTextStyle {
                    Text(
                        text = stringResource(id = R.string.set_default_launcher_tip),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            startWidget = {
                Icon(
                    imageVector = Icons.Rounded.TipsAndUpdates,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = null,
                )
            },
        )
    }
}

fun openAppInfo(context: Context) {
    val launcherApps = context.getSystemService<LauncherApps>()
    val componentName = ComponentName(context, SolinaLauncher::class.java)
    launcherApps?.startAppDetailsActivity(componentName, Process.myUserHandle(), null, null)
}
