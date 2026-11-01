package app.lawnchair.search.algorithms

import android.app.search.Query
import android.app.search.SearchContext
import android.app.search.SearchSession
import android.app.search.SearchTarget
import android.app.search.SearchUiManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import androidx.core.os.bundleOf
import app.lawnchair.compat.LawnchairQuickstepCompat
import app.lawnchair.preferences.PreferenceChangeListener
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.search.adapter.SearchTargetCompat
import app.lawnchair.util.requireSystemService
import com.android.launcher3.LauncherAppState
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.search.SearchCallback
import com.android.launcher3.util.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

class LawnchairASISearchAlgorithm(context: Context) :
    LawnchairSearchAlgorithm(context),
    PreferenceChangeListener {
    private val prefs: PreferenceManager
    private var searchSession: SearchSession? = null
    private var activeQuery: PendingQuery? = null

    init {
        prefs = PreferenceManager.getInstance(context)
        createSearchSession()
        prefs.searchResultShortcuts.addListener(this)
        prefs.searchResultPeople.addListener(this)
        prefs.searchResultPixelTips.addListener(this)
        prefs.searchResultSettings.addListener(this)
    }

    override fun onPreferenceChange() {
        cancel(interruptActiveRequests = true)
        createSearchSession()
    }

    override fun doSearch(query: String, callback: SearchCallback<BaseAllAppsAdapter.AdapterItem>) {
        activeQuery?.cancel()
        val pending = PendingQuery(query, callback)
        activeQuery = pending
        // Session creation is asynchronous. Keep the newest query pending instead of
        // returning an empty result when the drawer opens before the session is ready.
        searchSession?.let { submitQuery(it, pending) }
    }

    private fun submitQuery(session: SearchSession, pending: PendingQuery) {
        if (activeQuery !== pending) return
        val searchQuery = Query(pending.query, System.currentTimeMillis())
        try {
            session.query(searchQuery, Executors.MAIN_EXECUTOR, pending)
        } catch (_: RuntimeException) {
            if (activeQuery === pending) {
                activeQuery = null
                pending.deliverEmptyResult()
            }
        }
    }

    override fun cancel(interruptActiveRequests: Boolean) {
        activeQuery?.cancel()
        activeQuery = null
    }

    override fun destroy() {
        super.destroy()
        cancel(interruptActiveRequests = true)
        Executors.UI_HELPER_EXECUTOR.execute {
            searchSession?.destroy()
            searchSession = null
        }
        prefs.let {
            it.searchResultShortcuts.removeListener(this)
            it.searchResultPeople.removeListener(this)
            it.searchResultPixelTips.removeListener(this)
            it.searchResultSettings.removeListener(this)
        }
    }

    private fun createSearchSession() {
        if (LawnchairQuickstepCompat.ATLEAST_U) return
        val previousSession = searchSession
        searchSession = null
        Executors.UI_HELPER_EXECUTOR.execute {
            previousSession?.destroy()
            val idp = LauncherAppState.getIDP(context)
            val extras = bundleOf(
                "launcher.gridSize" to idp.numDatabaseAllAppsColumns,
                "allowlist_enabled" to false,
                "launcher.maxInlineIcons" to 3,
            )
            // apps or shortcuts
            var resultTypes = 1 or 2
            if (prefs.searchResultShortcuts.get()) {
                resultTypes = resultTypes or 1546
            }
            if (prefs.searchResultPeople.get()) {
                resultTypes = resultTypes or 4
            }
            if (prefs.searchResultPixelTips.get()) {
                resultTypes = resultTypes or 8192
                extras.putString("tips_source", "superpacks_tips_source")
            }
            if (prefs.searchResultSettings.get()) {
                resultTypes = resultTypes or 80
                extras.putString("settings_source", "superpacks_settings_source")
            }
            val searchContext = SearchContext(resultTypes, 200, extras)
            val searchSession = context.requireSystemService<SearchUiManager>()
                .createSearchSession(searchContext)
            Executors.MAIN_EXECUTOR.post {
                this.searchSession = searchSession
                activeQuery?.let { submitQuery(searchSession, it) }
            }
        }
    }

    private inner class PendingQuery(
        val query: String,
        private val callback: SearchCallback<BaseAllAppsAdapter.AdapterItem>,
    ) : Consumer<List<SearchTarget>> {
        private var canceled = false

        override fun accept(platformTargets: List<SearchTarget>) {
            if (!canceled) {
                val targets = platformTargets.map { SearchTargetCompat.wrap(it) }
                setFirstItemQuickLaunch(targets)
                val adapterItems = transformSearchResults(targets)
                callback.onSearchResult(
                    query,
                    ArrayList<BaseAllAppsAdapter.AdapterItem>(adapterItems),
                )
                if (activeQuery === this) {
                    activeQuery = null
                }
            }
        }

        fun cancel() {
            canceled = true
        }

        fun deliverEmptyResult() {
            if (!canceled) {
                callback.onSearchResult(query, arrayListOf())
            }
        }
    }

    companion object {
        fun checkSearchCompatibility(context: Context) {
            if (LawnchairQuickstepCompat.ATLEAST_U) return
            Executors.UI_HELPER_EXECUTOR.execute {
                val searchContext = SearchContext(1 or 2, 200, Bundle())
                val searchManager = context.requireSystemService<SearchUiManager>()
                val searchSession: SearchSession = searchManager.createSearchSession(searchContext)
                val searchQuery = Query("dummy", System.currentTimeMillis())
                val prefs = PreferenceManager.getInstance(context)
                val checkDone = AtomicBoolean(false)
                searchSession.query(searchQuery, Executors.MAIN_EXECUTOR) { targets ->
                    checkDone.set(true)
                    finishCompatibilityCheck(prefs, searchSession, targets.isNotEmpty())
                }
                Handler(Executors.UI_HELPER_EXECUTOR.looper).postDelayed(
                    {
                        if (!checkDone.get()) {
                            finishCompatibilityCheck(prefs, searchSession, false)
                        }
                    },
                    300,
                )
            }
        }

        private fun finishCompatibilityCheck(
            prefs: PreferenceManager,
            session: SearchSession,
            isCompatible: Boolean,
        ) {
            Executors.MAIN_EXECUTOR.execute { prefs.deviceSearch.set(isCompatible) }
            try {
                session.destroy()
            } catch (_: Exception) {
            }
        }
    }
}
