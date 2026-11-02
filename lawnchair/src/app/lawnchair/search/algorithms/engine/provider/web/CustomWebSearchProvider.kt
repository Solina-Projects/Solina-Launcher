package app.lawnchair.search.algorithms.engine.provider.web

import android.content.Context
import android.net.Uri
import android.util.Log
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * A WebSearchProvider that creates immutable, per-search configurations from user-defined URLs.
 */
object CustomWebSearchProvider : WebSearchProvider {
    private const val TAG = "CustomWebSearchProvider"

    override val id: String = "custom"
    override val label: Int = R.string.search_provider_custom
    override val iconRes: Int = R.drawable.ic_search
    private val okHttpClient = OkHttpClient()

    override fun configure(context: Context): WebSearchProvider {
        val prefs = PreferenceManager2.getInstance(context)
        return ConfiguredCustomWebSearchProvider(
            searchUrlTemplate = prefs.webSuggestionProviderUrl.firstCached(),
            suggestionsUrlTemplate = prefs.webSuggestionProviderSuggestionsUrl.firstCached(),
            displayName = prefs.webSuggestionProviderName.firstCached(),
            client = okHttpClient,
        )
    }

    // An unconfigured instance is intentionally inert. Call configure(context) before use.
    override fun getSuggestions(query: String): Flow<List<String>> = flow {
        emit(emptyList())
    }

    override fun getSearchUrl(query: String): String = GoogleWebSearchProvider.getSearchUrl(query)

    override fun toString(): String = id

    private class ConfiguredCustomWebSearchProvider(
        private val searchUrlTemplate: String,
        private val suggestionsUrlTemplate: String,
        override val displayName: String,
        private val client: OkHttpClient,
    ) : WebSearchProvider {
        override val id: String = CustomWebSearchProvider.id
        override val label: Int = CustomWebSearchProvider.label
        override val iconRes: Int = CustomWebSearchProvider.iconRes

        override fun getSuggestions(query: String): Flow<List<String>> = flow {
            if (query.isBlank() || suggestionsUrlTemplate.isBlank()) {
                emit(emptyList())
                return@flow
            }

            try {
                val encodedQuery = Uri.encode(query)
                val url = suggestionsUrlTemplate.replace("%s", encodedQuery)
                val request = Request.Builder().url(url).build()
                val call = client.newCall(request)
                val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
                    if (cause is CancellationException) call.cancel()
                }
                try {
                    call.execute().use { response ->
                        if (response.isSuccessful) {
                            emit(parseOpenSearchResponse(response.body.string()))
                        } else {
                            Log.w(TAG, "Failed to retrieve suggestions: ${response.code}")
                            emit(emptyList())
                        }
                    }
                } finally {
                    cancellationHandle.dispose()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                Log.e(TAG, "Error during suggestion retrieval", error)
                emit(emptyList())
            }
        }.flowOn(Dispatchers.IO)

        override fun getSearchUrl(query: String): String {
            if (searchUrlTemplate.isBlank()) {
                return GoogleWebSearchProvider.getSearchUrl(query)
            }
            return searchUrlTemplate.replace("%s", Uri.encode(query))
        }

        private fun parseOpenSearchResponse(responseBody: String): List<String> {
            return try {
                val jsonArray = JSONArray(responseBody)
                val suggestionsArray = jsonArray.getJSONArray(1)
                (0 until suggestionsArray.length()).map { suggestionsArray.getString(it) }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to parse custom provider response", error)
                emptyList()
            }
        }

        override fun toString(): String = id
    }
}
