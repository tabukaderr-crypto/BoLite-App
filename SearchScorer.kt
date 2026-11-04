package com.example.util

import com.example.data.SavedCard

data class ScoredCard(
    val card: SavedCard,
    val score: Int
)

object SearchScorer {
    /**
     * Searches local Room cards by keyword similarity across title, text, and category.
     * Scoring:
     * - exact word match = 3 points
     * - partial match = 2 points
     * - category match = 1 point
     * Sorted by score descending, then by savedAt descending.
     */
    fun scoreAndRank(cards: List<SavedCard>, query: String): List<SavedCard> {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty()) return emptyList()

        val punctuationRegex = "[\\s,،.?!؟:;()\\[\\]{}\"'\u060C\u061F]+".toRegex()
        val queryTokens = cleanQuery
            .split(punctuationRegex)
            .filter { it.isNotBlank() }

        if (queryTokens.isEmpty()) return emptyList()

        return cards.mapNotNull { card ->
            val title = if (card.text.length > 60) card.text.substring(0, 60) else card.text
            val cardFullText = card.text.lowercase()
            val cardTitle = title.lowercase()
            val cardCategory = card.category.lowercase()

            val textWords = cardFullText.split(punctuationRegex).filter { it.isNotBlank() }.toSet()
            val titleWords = cardTitle.split(punctuationRegex).filter { it.isNotBlank() }.toSet()

            var totalScore = 0

            for (token in queryTokens) {
                var wordMatched = false

                // 1. Exact word match = 3 points (in title or text)
                if (titleWords.contains(token) || textWords.contains(token)) {
                    totalScore += 3
                    wordMatched = true
                }

                // 2. Partial match = 2 points (if not exact word match, but substring is found in title or text)
                if (!wordMatched && (cardTitle.contains(token) || cardFullText.contains(token))) {
                    totalScore += 2
                    wordMatched = true
                }

                // 3. Category match = 1 point
                if (cardCategory.contains(token) || token.contains(cardCategory)) {
                    totalScore += 1
                }
            }

            if (totalScore > 0) {
                ScoredCard(card, totalScore)
            } else {
                null
            }
        }
        .sortedWith(compareByDescending<ScoredCard> { it.score }.thenByDescending { it.card.savedAt })
        .map { it.card }
    }
}
