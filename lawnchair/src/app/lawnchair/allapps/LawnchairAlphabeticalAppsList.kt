package app.lawnchair.allapps

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.lawnchair.data.folder.FolderEntry
import app.lawnchair.data.folder.service.FolderService
import app.lawnchair.launcher
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.util.categorizeAppsWithSystemAndGoogle
import com.android.launcher3.InvariantDeviceProfile.OnIDPChangeListener
import com.android.launcher3.R
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.allapps.AlphabeticalAppsList
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem
import com.android.launcher3.allapps.PrivateProfileManager
import com.android.launcher3.allapps.WorkProfileManager
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.views.ActivityContext
import com.patrykmichalik.opto.core.onEach as onEachPreference
import java.util.Locale
import java.util.function.Predicate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen

@Suppress("SYNTHETIC_PROPERTY_WITHOUT_JAVA_ORIGIN")
class LawnchairAlphabeticalAppsList<T>(
    private val context: T,
    private val appsStore: AllAppsStore<T>,
    workProfileManager: WorkProfileManager?,
    privateProfileManager: PrivateProfileManager?,
) : AlphabeticalAppsList<T>(context, appsStore, workProfileManager, privateProfileManager),
    OnIDPChangeListener,
    DefaultLifecycleObserver
    where T : Context, T : ActivityContext {

    private var hiddenApps: Set<String> = emptySet()
    private val prefs2 = PreferenceManager2.getInstance(context)
    private val prefs = PreferenceManager.getInstance(context)

    private val folderList = mutableListOf<FolderEntry>()

    // Component keys are stable across AppInfo instances and avoid duplicate folder membership.
    private val folderedAppKeys = mutableSetOf<String>()

    private val categoryBar by lazy { AllAppsCategoryBar(context) }
    private var selectedCategoryId: String = AllAppsCategoryBar.CATEGORY_ALL

    init {
        context.launcher.deviceProfile.inv.addOnChangeListener(this)
        (context as? LifecycleOwner)?.lifecycle?.addObserver(this)
        try {
            prefs2.hiddenApps.onEachPreference(launchIn = context.launcher.lifecycleScope) {
                hiddenApps = it
                onAppsUpdated()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to initialize hidden apps", t)
        }
        observeFolders()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        context.launcher.deviceProfile.inv.removeOnChangeListener(this)
    }

    /**
     * Observe the StateFlow for the lifetime of the launcher instead of taking a one-time
     * LiveData snapshot. Folder edits, reordering and deletions are then reflected immediately.
     */
    private fun observeFolders() {
        FolderService.INSTANCE.get(context.launcher.application).getFoldersFlow()
            .retryWhen { cause, attempt ->
                Log.e(TAG, "Failed to observe App Drawer folders; retrying", cause)
                delay(minOf(30_000L, 1_000L * (1L shl attempt.coerceAtMost(5).toInt())))
                true
            }
            .onEach { folders ->
                folderList.clear()
                folderList.addAll(
                    folders.orEmpty().sortedWith(
                        compareBy<FolderEntry> { it.rank }
                            .thenBy { it.title.lowercase(Locale.ROOT) }
                            .thenBy { it.id },
                    ),
                )
                updateAdapterItems()
            }.launchIn(context.launcher.lifecycleScope)
    }

    override fun updateItemFilter(itemFilter: Predicate<ItemInfo>?) {
        mItemFilter = Predicate { info ->
            require(info is AppInfo) { "`info` must be an instance of `AppInfo`." }
            val componentKey = info.toComponentKey().toString()
            (itemFilter?.test(info) != false) && componentKey !in hiddenApps
        }
        onAppsUpdated()
    }

    override fun addAppsWithSections(appList: List<AppInfo?>?, startPosition: Int): Int {
        if (appList.isNullOrEmpty()) return startPosition
        val drawerListDefault = prefs.drawerList.get()
        val showCategoryBar = prefs.drawerShowCategoryBar.get()
        val caddyDisplayMode = prefs.drawerCaddyDisplayMode.get()
        folderedAppKeys.clear()
        var position = startPosition

        // Keep work and private profiles on the platform implementation. Folder records belong
        // to the personal profile and must never leak across profile boundaries.
        if (isWorkOrPrivateSpace(appList)) return super.addAppsWithSections(appList, position)

        val validApps = appList.filterNotNull()
        val categorizedApps = categorizeAppsWithSystemAndGoogle(validApps, context)

        // Resolve custom/smart folders if any exist
        val resolvedFolderEntries = mutableListOf<Pair<FolderEntry, List<AppInfo>>>()
        val assignedFolderAppKeys = mutableSetOf<String>()
        folderList.forEach { folderEntry ->
            if (folderEntry.hide) return@forEach
            val resolvedApps = folderEntry.itemComponentKeys
                .asSequence()
                .filterNot { it in hiddenApps || it in assignedFolderAppKeys }
                .distinct()
                .mapNotNull { keyString ->
                    val componentKey = ComponentKey.fromString(keyString)
                        ?: return@mapNotNull null
                    appsStore.getApp(componentKey) as? AppInfo
                }
                .distinctBy { it.toComponentKey().toString() }
                .toList()
            if (resolvedApps.size > 1) {
                resolvedFolderEntries.add(folderEntry to resolvedApps)
                resolvedApps.forEach { app ->
                    val key = app.toComponentKey().toString()
                    assignedFolderAppKeys.add(key)
                    if (prefs.folderApps.get()) folderedAppKeys.add(key)
                }
            }
        }

        val categoryLookup = linkedMapOf<String, Pair<String, List<AppInfo>>>()
        val chips = mutableListOf<DrawerCategoryChip>()
        chips.add(
            DrawerCategoryChip(
                id = AllAppsCategoryBar.CATEGORY_ALL,
                title = context.getString(R.string.all_apps_category_all),
                count = validApps.size,
            ),
        )

        if (drawerListDefault && resolvedFolderEntries.isNotEmpty()) {
            resolvedFolderEntries.forEach { (entry, apps) ->
                val chipId = "folder:${entry.id}"
                val displayTitle = AllAppsCardStyler.localizeCategoryTitle(context, entry.title)
                categoryLookup[chipId] = displayTitle to apps
                chips.add(
                    DrawerCategoryChip(
                        id = chipId,
                        title = displayTitle,
                        count = apps.size,
                    ),
                )
            }
        } else {
            categorizedApps.forEach { (category, apps) ->
                if (apps.isNotEmpty()) {
                    val chipId = "cat:$category"
                    val displayTitle = AllAppsCardStyler.localizeCategoryTitle(context, category)
                    categoryLookup[chipId] = displayTitle to apps
                    chips.add(
                        DrawerCategoryChip(
                            id = chipId,
                            title = displayTitle,
                            count = apps.size,
                        ),
                    )
                }
            }
        }

        if (selectedCategoryId != AllAppsCategoryBar.CATEGORY_ALL &&
            selectedCategoryId !in categoryLookup
        ) {
            selectedCategoryId = AllAppsCategoryBar.CATEGORY_ALL
        }

        if (showCategoryBar && chips.size > 1) {
            categoryBar.bindCategories(
                chips = chips,
                selectedId = selectedCategoryId,
                onSelect = { newCategoryId ->
                    if (selectedCategoryId != newCategoryId) {
                        selectedCategoryId = newCategoryId
                        updateAdapterItems()
                    }
                },
            )
            mAdapterItems.add(AdapterItem.asCategoryBar(categoryBar))
            position++
        }

        val filteredSelection = if (showCategoryBar && selectedCategoryId != AllAppsCategoryBar.CATEGORY_ALL) {
            categoryLookup[selectedCategoryId]
        } else {
            null
        }

        if (filteredSelection != null) {
            val (sectionTitle, sectionApps) = filteredSelection
            mAdapterItems.add(AdapterItem.asCategoryHeader(sectionTitle, sectionApps.size))
            position++
            return super.addAppsWithSections(sectionApps, position)
        }

        if (!drawerListDefault) {
            if (caddyDisplayMode == "expanded_sections") {
                categorizedApps.forEach { (category, apps) ->
                    if (apps.isNotEmpty()) {
                        val displayTitle = AllAppsCardStyler.localizeCategoryTitle(context, category)
                        mAdapterItems.add(AdapterItem.asCategoryHeader(displayTitle, apps.size))
                        position++
                        apps.forEach { app ->
                            mAdapterItems.add(AdapterItem.asApp(app))
                            position++
                        }
                    }
                }
            } else {
                categorizedApps.forEach { (category, apps) ->
                    val displayTitle = AllAppsCardStyler.localizeCategoryTitle(context, category)
                    if (apps.size == 1) {
                        mAdapterItems.add(AdapterItem.asApp(apps.first()))
                    } else if (apps.size > 1) {
                        val folderInfo = FolderInfo().apply {
                            title = displayTitle
                            apps.forEach { add(it) }
                        }
                        mAdapterItems.add(AdapterItem.asFolder(folderInfo))
                    }
                    position++
                }
            }
        } else {
            resolvedFolderEntries.forEach { (folderEntry, resolvedApps) ->
                val folderInfo = FolderInfo().apply {
                    id = folderEntry.id
                    title = AllAppsCardStyler.localizeCategoryTitle(context, folderEntry.title)
                    resolvedApps.forEach { add(it) }
                }
                mAdapterItems.add(AdapterItem.asFolder(folderInfo))
                position++
            }

            val remainingApps = validApps.filterNot { app ->
                prefs.folderApps.get() && app.toComponentKey().toString() in folderedAppKeys
            }
            position = super.addAppsWithSections(remainingApps, position)
        }

        return position
    }

    override fun onIdpChanged(modelPropertiesChanged: Boolean) {
        onAppsUpdated()
    }
}
