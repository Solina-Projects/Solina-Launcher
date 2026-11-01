package app.lawnchair.allapps

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.lawnchair.data.folder.FolderEntry
import app.lawnchair.data.folder.model.FolderViewModel
import app.lawnchair.launcher
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.util.categorizeAppsWithSystemAndGoogle
import com.android.launcher3.InvariantDeviceProfile.OnIDPChangeListener
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

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

    private val viewModel = FolderViewModel(
        (context as? ComponentActivity)?.application ?: context.launcher.application,
    )
    private val folderList = mutableListOf<FolderEntry>()
    // Component keys are stable across AppInfo instances and avoid duplicate folder membership.
    private val folderedAppKeys = mutableSetOf<String>()

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
        viewModel.folders.onEach { folders ->
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
        folderedAppKeys.clear()
        var position = startPosition

        // Keep work and private profiles on the platform implementation. Folder records belong
        // to the personal profile and must never leak across profile boundaries.
        if (isWorkOrPrivateSpace(appList)) return super.addAppsWithSections(appList, position)

        if (!drawerListDefault) {
            val validApps = appList.filterNotNull()
            val categorizedApps = categorizeAppsWithSystemAndGoogle(validApps, context)

            categorizedApps.forEach { (category, apps) ->
                if (apps.size == 1) {
                    mAdapterItems.add(AdapterItem.asApp(apps.first()))
                } else if (apps.size > 1) {
                    val folderInfo = FolderInfo().apply {
                        title = category
                        apps.forEach { add(it) }
                    }
                    mAdapterItems.add(AdapterItem.asFolder(folderInfo))
                }
                position++
            }
        } else {
            folderList.forEach { folderEntry ->
                // A hidden folder is not rendered; its apps remain available in the normal list.
                if (folderEntry.hide) return@forEach

                val resolvedApps = folderEntry.itemComponentKeys
                    .asSequence()
                    .filterNot { it in hiddenApps }
                    .distinct()
                    .mapNotNull { keyString ->
                        val componentKey = ComponentKey.fromString(keyString)
                            ?: return@mapNotNull null
                        appsStore.getApp(componentKey) as? AppInfo
                    }
                    .distinctBy { it.toComponentKey().toString() }
                    .toList()

                // Empty and singleton folders are not useful as folders. Their apps fall through
                // to the regular app list instead of disappearing.
                if (resolvedApps.size > 1) {
                    val folderInfo = FolderInfo().apply {
                        id = folderEntry.id
                        title = folderEntry.title
                        resolvedApps.forEach { add(it) }
                    }
                    mAdapterItems.add(AdapterItem.asFolder(folderInfo))
                    position++

                    if (prefs.folderApps.get()) {
                        resolvedApps.forEach { folderedAppKeys.add(it.toComponentKey().toString()) }
                    }
                }
            }

            val remainingApps = appList.filterNotNull().filterNot { app ->
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
