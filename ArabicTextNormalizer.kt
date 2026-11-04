package com.example.util

/**
 * Makes Arabic text comparable, so searches match however people spell things:
 * "الإحصاء" = "احصاء" = "الاحصاء", "مُلخَّص" = "ملخص", "ماده" = "مادة", "١٢٣" = "123".
 * Pure Kotlin (no Android classes) so it is easy to test.
 */
object ArabicTextNormalizer {
    private val diacritics = Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
    private val prefixes = listOf("وال", "بال", "فال", "كال", "ال")

    /** Lower-case, strip diacritics/tatweel, unify letter forms, digits, punctuation, "ال". */
    fun normalize(input: String): String {
        if (input.isEmpty()) return ""
        val cleaned = diacritics.replace(input.lowercase(), "").replace("\u0640", "")
        val sb = StringBuilder(cleaned.length)
        for (ch in cleaned) {
            val mapped: Char = when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى', 'ئ' -> 'ي'
                'ة' -> 'ه'
                'ؤ' -> 'و'
                in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')
                in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')
                else -> ch
            }
            sb.append(if (mapped.isLetterOrDigit()) mapped else ' ')
        }
        return sb.toString()
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { stripPrefix(it) }
    }

    private fun stripPrefix(word: String): String {
        for (p in prefixes) {
            if (word.startsWith(p) && word.length - p.length >= 3) return word.substring(p.length)
        }
        return word
    }

    /** Words of a search query, normalized. */
    fun tokens(query: String): List<String> =
        normalize(query).split(' ').filter { it.isNotBlank() }

    /** True when EVERY query word appears somewhere in the (already normalized) text. */
    fun matchesAll(normalizedText: String, queryTokens: List<String>): Boolean =
        queryTokens.isEmpty() || queryTokens.all { normalizedText.contains(it) }
}
