package com.example.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.max
import kotlin.math.min

data class FuzzyMatchResult(
    val isMatch: Boolean,
    val score: Double,
    val matchedRanges: List<IntRange> = emptyList()
)

object FuzzySearchUtility {

    // Common Arabic academic and general offline synonyms/equivalents
    private val synonymGroups = listOf(
        listOf("دكتور", "دكتورة", "د.", "استاذ", "أستاذ", "بروفيسور", "معيد", "مدرس"),
        listOf("مادة", "ماده", "كورس", "مساق", "منهج", "مقرر"),
        listOf("امتحان", "اختبار", "فاينل", "ميد", "كويز", "quiz", "exam", "test"),
        listOf("وظيفة", "وظيفه", "شغل", "عمل", "تدريب", "فرصة", "فرصه", "internship", "job"),
        listOf("تسجيل", "ريكورد", "فويس", "محاضرة", "محاضره", "audio", "record"),
        listOf("ملف", "pdf", "سلايدات", "slides", "ملزمة", "ملزمه", "ملخص", "تجميعات", "درايف", "drive"),
        listOf("سكن", "سكنات", "شقة", "شقه", "غرفة", "غرفه", "إيجار"),
        listOf("كافتيريا", "كافتريا", "مطعم", "أكل", "وجبة", "وجبه"),
        listOf("سلام", "السلام", "السلام عليكم", "مرحبا", "تحية", "أهلا"),
        listOf("جدول", "مواعيد", "أوقات", "وقت", "شعبة", "شعبه", "قاعة", "قاعه")
    )

    /**
     * Normalizes Arabic text for tolerant matching:
     * - Normalizes Alef forms (أ, إ, آ, ٱ -> ا)
     * - Normalizes Yaa forms (ى -> ي)
     * - Normalizes Taa Marbuta (ة -> ه)
     * - Strips Tashkeel (diacritics) and Tatweel (ـ)
     * - Lowercases ASCII characters
     */
    fun normalizeArabic(text: String): String {
        if (text.isEmpty()) return ""

        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                // Diacritics (Tashkeel) & Tatweel -> skip
                '\u064B', '\u064C', '\u064D', '\u064E', '\u064F',
                '\u0650', '\u0651', '\u0652', '\u0670', '\u0640' -> continue

                // Alef variations -> standard Alef
                '\u0622', '\u0623', '\u0625', '\u0671' -> sb.append('\u0627')

                // Alef Maksura -> Yaa
                '\u0649' -> sb.append('\u064A')

                // Taa Marbuta -> Haa
                '\u0629' -> sb.append('\u0647')

                else -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    /**
     * Computes fuzzy score and matched character ranges for a multi-word or single-word query
     * against target text. Handles typos, partial matches, and Arabic synonyms.
     */
    fun matchFuzzy(targetText: String, rawQuery: String): FuzzyMatchResult {
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            return FuzzyMatchResult(isMatch = true, score = 1.0, matchedRanges = emptyList())
        }

        val normTarget = normalizeArabic(targetText)
        val normQuery = normalizeArabic(query)

        // 1. Direct exact or substring match in normalized text
        val directIndex = normTarget.indexOf(normQuery)
        if (directIndex >= 0) {
            val range = directIndex until (directIndex + normQuery.length)
            return FuzzyMatchResult(
                isMatch = true,
                score = 1.0,
                matchedRanges = listOf(range)
            )
        }

        // 2. Tokenize query into distinct search terms
        val queryTerms = normQuery.split("\\s+".toRegex()).filter { it.length >= 2 }
        if (queryTerms.isEmpty()) {
            return FuzzyMatchResult(isMatch = false, score = 0.0)
        }

        // Tokenize target into words along with their starting character offsets
        val targetTokens = extractTokensWithOffsets(targetText, normTarget)

        var totalMatchedScore = 0.0
        val foundRanges = mutableListOf<IntRange>()

        for (qTerm in queryTerms) {
            var bestTermScore = 0.0
            var bestRange: IntRange? = null

            // Expand query term with known offline synonyms
            val termVariants = getTermVariants(qTerm)

            for (targetTok in targetTokens) {
                // a. Check if any synonym or variant directly matches target token
                for (variant in termVariants) {
                    if (targetTok.normWord == variant || targetTok.normWord.contains(variant) || variant.contains(targetTok.normWord)) {
                        val score = if (targetTok.normWord == variant) 1.0 else 0.85
                        if (score > bestTermScore) {
                            bestTermScore = score
                            bestRange = targetTok.originalRange
                        }
                    }
                }

                // b. Typo tolerance: Levenshtein distance
                val levSim = calculateSimilarity(qTerm, targetTok.normWord)
                if (levSim > bestTermScore && levSim >= 0.65) {
                    bestTermScore = levSim
                    bestRange = targetTok.originalRange
                }
            }

            if (bestTermScore >= 0.65) {
                totalMatchedScore += bestTermScore
                if (bestRange != null) {
                    foundRanges.add(bestRange)
                }
            }
        }

        val overallScore = totalMatchedScore / queryTerms.size
        val isMatch = overallScore >= 0.60 || (queryTerms.size == 1 && totalMatchedScore >= 0.65)

        return FuzzyMatchResult(
            isMatch = isMatch,
            score = overallScore,
            matchedRanges = mergeOverlappingRanges(foundRanges)
        )
    }

    /**
     * Builds a Jetpack Compose AnnotatedString with matched keyword segments highlighted in orange.
     */
    fun buildHighlightedAnnotatedString(
        originalText: String,
        matchedRanges: List<IntRange>,
        highlightColor: Color = Color(0xFFFF6B35),
        normalColor: Color = Color(0xFFF0F0F2)
    ): AnnotatedString {
        if (matchedRanges.isEmpty()) {
            return AnnotatedString(originalText)
        }

        return buildAnnotatedString {
            var currentIndex = 0
            val sortedRanges = matchedRanges.sortedBy { it.first }

            for (range in sortedRanges) {
                val start = max(0, min(range.first, originalText.length))
                val end = max(start, min(range.last + 1, originalText.length))

                if (start > currentIndex) {
                    pushStyle(SpanStyle(color = normalColor))
                    append(originalText.substring(currentIndex, start))
                    pop()
                }

                if (end > start) {
                    pushStyle(
                        SpanStyle(
                            color = highlightColor,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    append(originalText.substring(start, end))
                    pop()
                }

                currentIndex = max(currentIndex, end)
            }

            if (currentIndex < originalText.length) {
                pushStyle(SpanStyle(color = normalColor))
                append(originalText.substring(currentIndex))
                pop()
            }
        }
    }

    private data class TokenWithOffset(
        val normWord: String,
        val originalRange: IntRange
    )

    private fun extractTokensWithOffsets(original: String, normalized: String): List<TokenWithOffset> {
        val result = mutableListOf<TokenWithOffset>()
        var i = 0
        val len = min(original.length, normalized.length)

        while (i < len) {
            while (i < len && original[i].isWhitespace()) {
                i++
            }
            if (i >= len) break
            val start = i
            while (i < len && !original[i].isWhitespace()) {
                i++
            }
            val end = i
            val normWord = normalized.substring(start, end).trim()
            if (normWord.length >= 2) {
                result.add(TokenWithOffset(normWord, start until end))
            }
        }
        return result
    }

    private fun getTermVariants(normTerm: String): List<String> {
        val variants = mutableListOf(normTerm)
        for (group in synonymGroups) {
            val normGroup = group.map { normalizeArabic(it) }
            if (normGroup.contains(normTerm)) {
                variants.addAll(normGroup)
            }
        }
        return variants.distinct()
    }

    private fun calculateSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        val maxLen = max(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        val distance = levenshteinDistance(s1, s2)
        return 1.0 - (distance.toDouble() / maxLen.toDouble())
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    dp[i - 1][j] + 1,
                    min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    private fun mergeOverlappingRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.size <= 1) return ranges
        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        var current = sorted[0]

        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.first <= current.last + 1) {
                current = current.first..max(current.last, next.last)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }
}
