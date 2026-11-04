package com.example.util

object TimeFilterUtility {

    const val ONLY_LAST_7_DAYS = false

    /**
     * Checks if a post timestamp is within the last 7 days.
     * Keeps posts from today, yesterday, hours ago, or 1-7 days ago.
     * Rejects posts older than 7 days (e.g. 8+ days, weeks, months, years).
     */
    fun isWithinLast7Days(timestamp: String?): Boolean {
        if (!ONLY_LAST_7_DAYS) return true
        if (timestamp.isNullOrBlank()) return true // default keep if time not isolated

        val t = timestamp.trim().lowercase()

        // 1. Definite OLD indicators (> 7 days)
        // Weeks, months, years
        if (t.contains("أسبوعين") || t.contains("أسابيع") ||
            t.contains("شهر") || t.contains("أشهر") || t.contains("شهور") ||
            t.contains("سنة") || t.contains("سنوات") || t.contains("عام") ||
            t.contains("2w") || t.contains("3w") || t.contains("4w") ||
            t.contains("mo") || t.contains("month") || t.contains("year") ||
            t.contains("1y") || t.contains("2y")
        ) {
            return false
        }

        // Check for 8+ days in Arabic (e.g. "منذ 8 أيام", "منذ 15 يوم")
        val daysPatternArabic = Regex("""(\d+)\s*(أيام|يوم|ايام)""")
        val matchArabic = daysPatternArabic.find(t)
        if (matchArabic != null) {
            val num = matchArabic.groupValues[1].toIntOrNull() ?: 1
            if (num > 7) return false
        }

        // Check for 8+ days in English (e.g. "8d", "14 days")
        val daysPatternEnglish = Regex("""(\d+)\s*(d|day|days)""")
        val matchEnglish = daysPatternEnglish.find(t)
        if (matchEnglish != null) {
            val num = matchEnglish.groupValues[1].toIntOrNull() ?: 1
            if (num > 7) return false
        }

        // Check for older years (2020-2025)
        if (Regex("""202[0-5]""").containsMatchIn(t)) {
            return false
        }

        return true
    }
}
