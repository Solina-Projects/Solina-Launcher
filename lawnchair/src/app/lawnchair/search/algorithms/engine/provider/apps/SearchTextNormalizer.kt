package app.lawnchair.search.algorithms.engine.provider.apps

import java.text.Normalizer
import java.util.Locale

/**
 * Shared canonicalization for app names and user-entered search queries.
 * Folds Latin accents while preserving meaningful combining marks in other scripts.
 */
object SearchTextNormalizer {
    private val spaces = Regex("\\s+")

    fun normalize(value: String): String {
        val input = Normalizer.normalize(value, Normalizer.Form.NFKD)
        val canonical = StringBuilder(input.length)
        var previousBaseScript = Character.UnicodeScript.COMMON
        var index = 0

        while (index < input.length) {
            val codePoint = input.codePointAt(index)
            val type = Character.getType(codePoint)
            val isCombiningMark = type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()

            if (!isCombiningMark || previousBaseScript != Character.UnicodeScript.LATIN) {
                when (codePoint) {
                    'ي'.code, 'ى'.code -> canonical.append('ی')
                    'ك'.code -> canonical.append('ک')
                    0x0640, 0x200c, 0x200d, 0xfeff, 0x200e, 0x200f -> Unit
                    in 0x0660..0x0669 -> canonical.append(('0'.code + codePoint - 0x0660).toChar())
                    in 0x06f0..0x06f9 -> canonical.append(('0'.code + codePoint - 0x06f0).toChar())
                    else -> canonical.appendCodePoint(codePoint)
                }
            }

            if (!isCombiningMark) {
                previousBaseScript = Character.UnicodeScript.of(codePoint)
            }
            index += Character.charCount(codePoint)
        }

        return spaces.replace(canonical.toString().lowercase(Locale.ROOT), " ").trim()
    }
}
