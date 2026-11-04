package com.example

import com.example.data.SavedCard
import com.example.util.CategoryMatcher
import com.example.util.SearchScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun testDoctorCategoryMatching() {
        assertEquals("دكتور", CategoryMatcher.match("سؤال بخصوص محاضرة دكتور أحمد غداً"))
        assertEquals("دكتور", CategoryMatcher.match("هل الدكتورة سارة حددت موعد الساعات المكتبية؟"))
    }

    @Test
    fun testSubjectCategoryMatching() {
        assertEquals("مادة", CategoryMatcher.match("متى موعد كويز مادة الرياضيات شابتر 3؟"))
        assertEquals("مادة", CategoryMatcher.match("مقرر الاحصاء فيه فاينل لو مشروع؟"))
    }

    @Test
    fun testAttachmentCategoryMatching() {
        assertEquals("مرفق", CategoryMatcher.match("أين أجد ملف pdf وسلايدات المحاضرة السابقة؟"))
        assertEquals("مرفق", CategoryMatcher.match("رابط درايف التجميعات والملخص"))
    }

    @Test
    fun testAudioCategoryMatching() {
        assertEquals("تسجيل", CategoryMatcher.match("ممكن تسجيل أو ريكورد محاضرة اليوم يا شباب؟"))
        assertEquals("تسجيل", CategoryMatcher.match("فويس شرح الدكتور للمسألة"))
    }

    @Test
    fun testSearchScorerRanking() {
        val cards = listOf(
            SavedCard(id = 1, text = "محاضرة عامة عن التاريخ", category = "مادة"),
            SavedCard(id = 2, text = "من هو أفضل دكتور لمقرر العربي 1؟", category = "دكتور"),
            SavedCard(id = 3, text = "العربي مقرر سهل للجميع", category = "مادة")
        )

        // Query: "أفضل دكتور للعربي"
        // Card 2 has exact word match "أفضل" (3), "دكتور" (3) + category "دكتور" (1) = 7+ points
        // Should rank Card 2 first
        val results = SearchScorer.scoreAndRank(cards, "أفضل دكتور للعربي 1؟")
        assertTrue(results.isNotEmpty())
        assertEquals(2L, results[0].id)
    }

    @Test
    fun testSearchScorerCategoryMatch() {
        val cards = listOf(
            SavedCard(id = 1, text = "سلايدات المحاضرة الأولى", category = "مرفق"),
            SavedCard(id = 2, text = "تسجيل صوتي كامل", category = "تسجيل")
        )

        val results = SearchScorer.scoreAndRank(cards, "تسجيل")
        assertEquals(2L, results[0].id)
    }

    @Test
    fun testIsStudentRelevant() {
        assertTrue(CategoryMatcher.isStudentRelevant("من هو دكتور مادة التفاضل والتكامل؟"))
        assertTrue(CategoryMatcher.isStudentRelevant("من عنده تسجيل المحاضرة الأخيرة؟"))
        assertTrue(CategoryMatcher.isStudentRelevant("رابط ملف pdf التجميعات"))
        org.junit.Assert.assertFalse(CategoryMatcher.isStudentRelevant("مساء الخير يا شباب كيف الحال"))
    }

    @Test
    fun testPageParserUtilityRelevance() {
        val highSnippet = "سؤال عن دكتور مادة الرياضيات وامتحان الفاينل"
        val pair = com.example.util.PageParserUtility.parseScreen(null)
        assertEquals("صفحة عامة", pair.first)
    }

    @Test
    fun testFuzzySearchArabicNormalization() {
        val normalized = com.example.util.FuzzySearchUtility.normalizeArabic("أهْلاً وَسَهْلاً بِكُم في الدَّوْرَةِ")
        assertEquals("اهلا وسهلا بكم في الدوره", normalized)
    }

    @Test
    fun testFuzzySearchExactAndPartialMatch() {
        val text = "السلام عليكم ورحمة الله، بخصوص استفسارات دكتور خالد للمادة"
        val res = com.example.util.FuzzySearchUtility.matchFuzzy(text, "دكتور خالد")
        assertTrue(res.isMatch)
        assertTrue(res.score >= 0.8)
        assertTrue(res.matchedRanges.isNotEmpty())
    }

    @Test
    fun testFuzzySearchTypoTolerance() {
        val text = "أستاذ المادة أعلن عن موعد الاختبار النهائي"
        // Typo: "الامتحان" or "اختبار"
        val res = com.example.util.FuzzySearchUtility.matchFuzzy(text, "امتحان")
        assertTrue(res.isMatch)
    }

    @Test
    fun testFuzzySearchSynonyms() {
        val text = "فرصة عمل وتدريب للطلاب الخريجين"
        val res = com.example.util.FuzzySearchUtility.matchFuzzy(text, "وظيفة")
        assertTrue(res.isMatch)
    }

    @Test
    fun testLocalContextClassifier_Question() {
        val qText = "هل حد يعرف متى موعد تسليم مشروع التخرج؟"
        val classification = com.example.nlp.LocalContextClassifier.classifyContent(qText)
        assertEquals(com.example.nlp.PostContentType.QUESTION, classification.type)
        assertEquals("سؤال", classification.categoryName)
        assertTrue(classification.toAiPayloadJson().contains("\"category\": \"سؤال\""))
    }

    @Test
    fun testLocalContextClassifier_Announcement() {
        val aText = "إعلان هام وعاجل من شؤون الطلبة بخصوص مواعيد الامتحانات والجدول النهائي"
        val classification = com.example.nlp.LocalContextClassifier.classifyContent(aText)
        assertEquals(com.example.nlp.PostContentType.ANNOUNCEMENT, classification.type)
        assertEquals("إعلان", classification.categoryName)
    }

    @Test
    fun testLocalContextClassifier_ResourceShare() {
        val rText = "سلايدات ومذكرات المادة مع رابط درايف كامل وملف pdf"
        val classification = com.example.nlp.LocalContextClassifier.classifyContent(rText)
        assertEquals(com.example.nlp.PostContentType.RESOURCE_SHARE, classification.type)
        assertEquals("مشاركة موارد", classification.categoryName)
    }

    @Test
    fun testSpatialProximityAnalyzer_CoordinateNormalization() {
        val box = com.example.spatial.NormalizedBox(
            left = 0.1f,
            top = 0.2f,
            right = 0.9f,
            bottom = 0.6f
        )
        assertEquals(0.4f, box.centerY, 0.001f)
        assertEquals(0.4f, box.height, 0.001f)
    }
}
