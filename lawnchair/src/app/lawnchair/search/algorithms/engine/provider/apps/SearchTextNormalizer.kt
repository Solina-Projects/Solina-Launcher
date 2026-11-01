package app.lawnchair.search.algorithms.engine.provider.apps

import java.text.Normalizer
import java.util.Locale

/**
 * Shared canonicalization for app names and user-entered search queries.
 * Handles common Persian/Arabic keyboard variants without removing meaningful letters.
 */
object SearchTextNormalizer {
    private val marks = Regex("\\p{M}+")
    private val spaces = Regex("\\s+")

    fun normalize(value: String): String {
        val input = Normalizer.normalize(value, Normalizer.Form.NFKC)
        val canonical = buildString(input.length) {
            input.forEach { char ->
                when (char) {
                    'ي', 'ى' -> append('ی')
                    'ك' -> append('ک')
                    '\u0640', '\u200c', '\u200d', '\ufeff' -> append(' ')
                    in '\u0660'..'\u0669' -> append(('0'.code + char.code - '\u0660'.code).toChar())
                    in '\u06f0'..'\u06f9' -> append(('0'.code + char.code - '\u06f0'.code).toChar())
                    else -> append(char)
                }
            }
        }
        return spaces.replace(marks.replace(canonical, "").lowercase(Locale.ROOT), " ").trim()
    }
}
