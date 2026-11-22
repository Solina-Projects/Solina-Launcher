package app.lawnchair.search.algorithms

import rustanything.CalcRsNative
import rustanything.SearchRsNative

/**
 * Calls into the Rust ports (Wave 3) when their native libraries are
 * present, falling back to the Kotlin implementations otherwise.
 *
 * Availability is probed once and cached: loading a native class throws
 * UnsatisfiedLinkError when its .so is absent, and a failed class init
 * would throw NoClassDefFoundError on every later access, so the result
 * must not be re-probed per call.
 */
object RustSearch {

    private val calcNativeAvailable: Boolean = try {
        Class.forName("rustanything.CalcRsNative")
        true
    } catch (_: Throwable) {
        false
    }

    private val searchNativeAvailable: Boolean = try {
        Class.forName("rustanything.SearchRsNative")
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Rust port of `calculateEquation` (calc-rs). Returns the result, or
     * null when the native library is absent, the call fails, or the
     * calculation is invalid (the Rust port returns "" for that).
     */
    fun calculateEquation(query: String): String? {
        if (!calcNativeAvailable) return null
        return try {
            CalcRsNative.call_calculateEquation(query).takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    /** Rust port of `SearchTextNormalizer.normalize` (search-rs). */
    fun normalizeSearchText(value: String): String? {
        if (!searchNativeAvailable) return null
        return try {
            SearchRsNative.call_normalizeSearchText(value)
        } catch (_: Throwable) {
            null
        }
    }

    /** Rust port of the per-app WeightedRatio step (search-rs). */
    fun fuzzyScore(query: String, choice: String): Int? {
        if (!searchNativeAvailable) return null
        return try {
            SearchRsNative.call_fuzzyScore(query, choice).toInt()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Scores every choice against `query` with the batched Rust port and
     * returns the indexes of the choices scoring at least `min`, best
     * first (ties in choice order) — the same order
     * `FuzzySearch.extractSorted` produces. Returns null when the native
     * library is absent or the call fails; an empty choice list returns
     * an empty result without touching JNI.
     */
    fun rankChoices(query: String, choices: List<String>, min: Int): IntArray? {
        if (choices.isEmpty()) return intArrayOf()
        if (!searchNativeAvailable) return null
        return try {
            FuzzyScoreBatch(choices.toTypedArray()).use { batch ->
                batch.rank(query, min.toDouble())
            }
        } catch (_: Throwable) {
            null
        }
    }
}
