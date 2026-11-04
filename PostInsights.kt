package com.example.util

enum class PostKind(val label: String) {
    QUESTION("سؤال"),
    REQUEST_MATERIAL("طلب ملخص أو ملف"),
    SHARING("مشاركة مادة"),
    EXAM("امتحان"),
    MARKET("بيع أو سكن"),
    OTHER("عام")
}

data class PostInsight(
    val kind: PostKind,
    val isQuestion: Boolean,
    val course: String?,
    val doctor: String?
) {
    /** Short line for the post card, e.g. "سؤال • مادة: الفيزياء العامة • د. خالد". */
    fun summary(): String {
        val parts = mutableListOf<String>()
        if (kind != PostKind.OTHER) parts.add(kind.label)
        if (!course.isNullOrBlank()) parts.add("مادة: $course")
        if (!doctor.isNullOrBlank()) parts.add("د. $doctor")
        return parts.joinToString(" • ")
    }
}

/**
 * Understands a student post: is it a question, which course, which doctor.
 * Rule-based (works offline, no AI). Pure Kotlin, easy to test.
 */
object PostInsights {
    private val questionWords = setOf(
        "هل", "كيف", "ليش", "لماذا", "لما", "متي", "وين", "اين", "مين", "شو", "ايش",
        "كم", "بدي", "بدنا", "محتاج", "محتاجه", "ممكن", "احد", "حد", "حدا"
    )
    private val questionPhrases = listOf("حد عنده", "حدا عنده", "من عنده", "مين عنده", "مين بيعرف", "حدا بيعرف", "احد يعرف", "حد يعرف")
    private val marketWords = setOf("للبيع", "ابيع", "بيع", "للايجار", "ايجار", "سكن", "شقه", "مطلوب", "وظيفه", "توصيل", "اعلان", "خصم")
    private val examWords = setOf("امتحان", "اختبار", "فاينل", "فاينال", "ميد", "كويز", "ميدتيرم")
    private val materialWords = setOf("ملخص", "ملخصات", "ملزمه", "ملزمات", "سلايدات", "مرفق", "مرفقات", "pdf", "تسجيل", "تسجيلات", "شيت", "نوتس", "اسئله", "اسايمنت")
    private val stopWords = setOf(
        "هل", "في", "عند", "عندي", "ما", "ماذا", "مين", "حد", "حدا", "ممكن", "لو", "او", "و", "من", "الي", "اللي",
        "كيف", "ليش", "شو", "ايش", "وين", "متي", "بدي", "بدنا", "محتاج", "يا", "اخواني", "جماعه", "الله", "اريد", "ارجو",
        "عن", "على", "الى", "مع", "هاي", "هذه", "هذا", "لحد", "كل", "لل", "ل",
        "دكتور", "دكتوره", "دكتورنا", "استاذ", "امتحان", "اختبار", "فاينل", "ميد"
    )
    private val courseRegex = Regex("""(?:مادة|ماده|مساق|كورس|مقرر)\s+((?:[\u0621-\u064A0-9]+\s?){1,5})""")
    private val doctorRegex = Regex("""(?:الدكتورة|الدكتوره|الدكتور|دكتورة|دكتوره|دكتور|دكتورنا|الاستاذ|الأستاذ|استاذ|أستاذ|د\.|د/)\s*((?:[\u0621-\u064A]+\s?){1,4})""")

    fun analyze(text: String): PostInsight {
        val n = ArabicTextNormalizer.normalize(text)
        val words = n.split(' ').filter { it.isNotEmpty() }
        val wordSet = words.toSet()
        val hasQuestionMark = text.contains('؟') || text.contains('?')
        val isQuestion = hasQuestionMark ||
            questionPhrases.any { n.contains(it) } ||
            (words.isNotEmpty() && words.first() in questionWords) ||
            words.take(4).any { it in setOf("هل", "كيف", "ليش", "لماذا", "وين", "شو", "ايش") }
        val isMarket = wordSet.any { it in marketWords }
        val isExam = wordSet.any { it in examWords }
        val hasMaterial = wordSet.any { it in materialWords }
        val kind = when {
            isMarket -> PostKind.MARKET
            isQuestion && hasMaterial -> PostKind.REQUEST_MATERIAL
            isQuestion -> PostKind.QUESTION
            isExam -> PostKind.EXAM
            hasMaterial -> PostKind.SHARING
            else -> PostKind.OTHER
        }
        return PostInsight(
            kind = kind,
            isQuestion = isQuestion,
            course = firstGroup(courseRegex, text),
            doctor = firstGroup(doctorRegex, text)
        )
    }

    /** Words added to the search text so typing "سؤال" or a course/doctor name finds the post. */
    fun searchTags(text: String): String {
        val insight = analyze(text)
        return ArabicTextNormalizer.normalize(
            listOf(insight.kind.label, insight.course ?: "", insight.doctor ?: "").joinToString(" ")
        )
    }

    private fun firstGroup(regex: Regex, text: String): String? {
        val raw = regex.find(text)?.groupValues?.getOrNull(1) ?: return null
        val kept = mutableListOf<String>()
        for (w in raw.trim().split(Regex("\\s+"))) {
            val nw = ArabicTextNormalizer.normalize(w)
            if (nw.isEmpty() || nw in stopWords || nw.startsWith("لل")) break
            kept.add(w)
            if (kept.size >= 3) break
        }
        return kept.joinToString(" ").ifBlank { null }
    }
}
