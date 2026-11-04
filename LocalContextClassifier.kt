package com.example.nlp

enum class PostContentType {
    QUESTION,       // [سؤال]
    ANNOUNCEMENT,   // [إعلان]
    RESOURCE_SHARE, // [مشاركة موارد]
    DISCUSSION      // [نقاش]
}

data class ContentClassificationResult(
    val type: PostContentType,
    val categoryName: String,
    val confidenceScore: Float,
    val matchedKeywords: List<String>,
    val suggestedAction: String
) {
    /**
     * AI Hook: JSON representation ready for semantic summarization or LLM prompt feeding.
     */
    fun toAiPayloadJson(): String {
        val keywordsList = matchedKeywords.joinToString(separator = "\", \"", prefix = "[\"", postfix = "\"]")
        return """
            {
              "type": "${type.name}",
              "category": "$categoryName",
              "confidence": $confidenceScore,
              "matched_keywords": $keywordsList,
              "suggested_action": "$suggestedAction"
            }
        """.trimIndent()
    }
}

/**
 * LocalContextClassifier:
 * Context-aware NLP layer running 100% offline using optimized regex & Arabic dialect keywords.
 * Classifies posts into [سؤال], [إعلان], [مشاركة موارد], [نقاش] and outputs structured AI-ready data.
 */
object LocalContextClassifier {

    private val questionKeywords = listOf(
        "هل", "كيف", "متى", "أين", "وين", "ليش", "لماذا", "مين", "من هو", "من هي",
        "ممكن", "استفسار", "سؤال", "حد يعرف", "أحد يعرف", "لو سمحتم", "لو سمحت",
        "بالله", "عندي سؤال", "في حدا", "في أحد", "شلون", "إيش", "ايش", "شو"
    )

    private val announcementKeywords = listOf(
        "تنبيه", "إعلان", "اعلان", "هام", "عاجل", "رسمي", "تقرر", "نلفت عناية",
        "شؤون الطلاب", "شؤون الطلبة", "عمادة", "إدارة الكلية", "مجلس الكلية",
        "مواعيد", "جدول", "رسمياً", "امتحان", "اختبار", "فاينل", "ميدتيرم", "قاعة"
    )

    private val resourceKeywords = listOf(
        "سلايدات", "slides", "ملخص", "تلخيص", "ملزمة", "مذكرات", "pdf", "درايف",
        "drive", "drive.google", "رابط", "لينك", "link", "تجميعات", "بنك أسئلة",
        "اسئلة سنوات", "شيت", "حلول", "تسجيل", "ريكورد", "فويس", "محاضرة مسجلة", "كتاب"
    )

    private val discussionKeywords = listOf(
        "رأيكم", "شو رأيكم", "نقاش", "تجربة", "تجارب", "نصيحة", "أنصحكم",
        "وظيفة", "تدريب", "فرصة عمل", "شغل", "إنترنشيب", "internship", "خبرتكم"
    )

    fun classifyContent(text: String): ContentClassificationResult {
        val clean = text.trim()
        val lower = clean.lowercase()

        val matchedQ = questionKeywords.filter { lower.contains(it) }
        val matchedA = announcementKeywords.filter { lower.contains(it) }
        val matchedR = resourceKeywords.filter { lower.contains(it) }
        val matchedD = discussionKeywords.filter { lower.contains(it) }

        val hasQuestionMark = clean.contains("؟") || clean.contains("?")

        var questionScore = matchedQ.size * 2.0f + if (hasQuestionMark) 3.5f else 0f
        var announceScore = matchedA.size * 2.5f
        var resourceScore = matchedR.size * 3.0f + if (lower.contains("http") || lower.contains(".pdf")) 4.0f else 0f
        var discussScore = matchedD.size * 2.0f + 1.0f

        return when {
            resourceScore >= 3.0f && resourceScore >= questionScore && resourceScore >= announceScore -> {
                ContentClassificationResult(
                    type = PostContentType.RESOURCE_SHARE,
                    categoryName = "مشاركة موارد",
                    confidenceScore = (0.75f + (resourceScore * 0.05f)).coerceAtMost(0.99f),
                    matchedKeywords = matchedR,
                    suggestedAction = "تنزيل أو حفظ الملف للدفتر"
                )
            }
            announceScore >= 2.5f && announceScore >= questionScore -> {
                ContentClassificationResult(
                    type = PostContentType.ANNOUNCEMENT,
                    categoryName = "إعلان",
                    confidenceScore = (0.80f + (announceScore * 0.04f)).coerceAtMost(0.99f),
                    matchedKeywords = matchedA,
                    suggestedAction = "تثبيت الموعد في التقويم"
                )
            }
            questionScore >= 2.0f -> {
                ContentClassificationResult(
                    type = PostContentType.QUESTION,
                    categoryName = "سؤال",
                    confidenceScore = (0.75f + (questionScore * 0.05f)).coerceAtMost(0.98f),
                    matchedKeywords = matchedQ,
                    suggestedAction = "كتابة رد أو مساعدة الزميل"
                )
            }
            else -> {
                ContentClassificationResult(
                    type = PostContentType.DISCUSSION,
                    categoryName = "نقاش",
                    confidenceScore = 0.82f,
                    matchedKeywords = matchedD,
                    suggestedAction = "قراءة الآراء والتفاعل"
                )
            }
        }
    }
}
