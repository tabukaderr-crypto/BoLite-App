package com.example.util

object CategoryMatcher {
    val CATEGORIES = listOf("دكتور", "مادة", "مرفق", "تسجيل")

    private val audioKeywords = listOf(
        "تسجيل", "تسجيلات", "ريكورد", "ريكوردات", "فويس", "صوت",
        "مسجل", "مسجلة", "زوم", "zoom", "فيديو", "video", "audio", "record"
    )

    private val attachmentKeywords = listOf(
        "مرفق", "مرفقات", "pdf", "بي دي اف", "درايف", "drive",
        "رابط", "روابط", "لينك", "link", "ملف", "ملفات", "ملزمة", "ملزمه",
        "سلايد", "سلايدات", "slides", "تجميعات", "شيت", "sheet", "doc"
    )

    private val doctorKeywords = listOf(
        "دكتور", "دكتورة", "دكتوره", "دكاترة", "دكاتره", "استاذ", "أستاذ",
        "معيد", "معيدة", "بروفيسور", "دكتورنا", "دكتورتنا", "د.", "د/"
    )

    private val subjectKeywords = listOf(
        "مادة", "ماده", "كورس", "منهج", "مقرر", "شابتر", "شبتر",
        "امتحان", "اختبار", "فاينل", "ميد", "كويز", "اسايمنت", "واجب",
        "درجات", "علامات", "quiz", "exam"
    )

    fun match(text: String): String {
        val clean = text.trim().lowercase()

        // Check for specific matches in priority order
        if (audioKeywords.any { clean.contains(it) }) {
            return "تسجيل"
        }
        if (attachmentKeywords.any { clean.contains(it) }) {
            return "مرفق"
        }
        if (doctorKeywords.any { clean.contains(it) }) {
            return "دكتور"
        }
        if (subjectKeywords.any { clean.contains(it) }) {
            return "مادة"
        }

        // Default category fallback
        return "مادة"
    }

    fun isStudentRelevant(text: String): Boolean {
        val clean = text.trim().lowercase()
        if (clean.length < 8) return false
        return audioKeywords.any { clean.contains(it) } ||
               attachmentKeywords.any { clean.contains(it) } ||
               doctorKeywords.any { clean.contains(it) } ||
               subjectKeywords.any { clean.contains(it) }
    }
}
