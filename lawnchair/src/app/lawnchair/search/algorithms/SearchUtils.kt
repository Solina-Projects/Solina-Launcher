package app.lawnchair.search.algorithms

import android.content.Context
import android.content.pm.ShortcutInfo
import app.lawnchair.launcher
import app.lawnchair.ui.preferences.components.HiddenAppsInSearch
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.popup.PopupPopulator
import com.android.launcher3.search.StringMatcherUtility
import com.android.launcher3.shortcuts.ShortcutRequest
import java.text.Normalizer
import java.util.Locale
import me.xdrop.fuzzywuzzy.FuzzySearch
import me.xdrop.fuzzywuzzy.algorithms.WeightedRatio

object SearchUtils {
    fun normalSearch(apps: List<AppInfo>, query: String, maxResultsCount: Int, hiddenApps: Set<String>, hiddenAppsInSearch: String): List<AppInfo> {
        // Do an intersection of the words in the query and each title, and filter out all the
        // apps that don't match all of the words in the query.
        val queryTextLower = normalizeSearchText(query)
        if (queryTextLower.isEmpty() || maxResultsCount <= 0) return emptyList()
        val matcher = StringMatcherUtility.StringMatcher.getInstance()
        return apps.asSequence()
            .filter { StringMatcherUtility.matches(queryTextLower, normalizeSearchText(it.title.toString()), matcher) }
            .filterHiddenApps(queryTextLower, hiddenApps, hiddenAppsInSearch)
            .take(maxResultsCount)
            .toList()
    }

    fun fuzzySearch(apps: List<AppInfo>, query: String, maxResultsCount: Int, hiddenApps: Set<String>, hiddenAppsInSearch: String): List<AppInfo> {
        val queryTextLower = normalizeSearchText(query)
        if (queryTextLower.isEmpty() || maxResultsCount <= 0) return emptyList()
        val filteredApps = apps.asSequence()
            .filterHiddenApps(queryTextLower, hiddenApps, hiddenAppsInSearch)
            .toList()
        val matches = FuzzySearch.extractSorted(
            queryTextLower,
            filteredApps,
            { normalizeSearchText(it.sectionName + it.title) },
            WeightedRatio(),
            65,
        )

        return matches.take(maxResultsCount)
            .map { it.referent }
    }

    fun getShortcuts(app: AppInfo, context: Context): List<ShortcutInfo> {
        val shortcuts = ShortcutRequest(context.launcher, app.user)
            .withContainer(app.targetComponent)
            .query(ShortcutRequest.PUBLISHED)
        return PopupPopulator.sortAndFilterShortcuts(shortcuts)
    }
}

private fun normalizeSearchText(value: String): String {
    return Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .trim()
}

fun Sequence<AppInfo>.filterHiddenApps(
    query: String,
    hiddenApps: Set<String>,
    hiddenAppsInSearch: String,
): Sequence<AppInfo> {
    return when (hiddenAppsInSearch) {
        HiddenAppsInSearch.ALWAYS -> {
            this
        }

        HiddenAppsInSearch.IF_NAME_TYPED -> {
            filter {
                it.toComponentKey().toString() !in hiddenApps ||
                    normalizeSearchText(it.title.toString()) == query
            }
        }

        else -> {
            filter { it.toComponentKey().toString() !in hiddenApps }
        }
    }
}
