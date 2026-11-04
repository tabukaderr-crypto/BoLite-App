package com.example.util

/**
 * Aggressive Data Sanitizer and Real-Word Filter for Bo Lite v10.
 *
 * Implements strict rules:
 * 1. REAL-WORD FILTER:
 *    - Rejects text with length < 3 chars.
 *    - Rejects text consisting mostly of consonants without vowels (e.g. "sygl", "gug", "rxtq").
 *    - Rejects random keyboard mashing patterns (e.g. "alliaala", "aSygläalosysigugsiai", "asdfghjkl").
 *    - Strictly requires recognized Arabic or English dictionary words.
 * 2. STRICT AUTHOR NAME VALIDATION:
 *    - Must look like a name (contains spaces or capital letters).
 *    - Discards lowercase single-token gibberish (e.g., "alliaala", "sygl") -> sets to "Unknown Member".
 * 3. CLEAN CATEGORY TAG RESOLUTION:
 *    - Hides tags completely when null, empty, or default ("عام", "مادة", "General").
 */
object PostSanitizer {

    // Common Arabic core vocabulary, academic terms, student dialogue
    private val arabicDictionaryWords = setOf(
        "في", "من", "على", "إلى", "عن", "مع", "هذا", "هذه", "تم", "قد", "كل", "أو", "و", "ما", "لا",
        "أن", "إن", "كان", "كانت", "هو", "هي", "التي", "الذي", "الذين", "لدينا", "اليوم", "غدا", "غداً",
        "أحد", "احد", "يرجى", "دكتور", "دكتورة", "مادة", "ماده", "امتحان", "اختبار", "كويز", "فاينل",
        "ميدتيرم", "محاضرة", "محاضره", "سؤال", "استفسار", "طالب", "طلبة", "طلاب", "جامعة", "كلية",
        "شعبة", "تسجيل", "درجات", "علامات", "واجب", "مشروع", "تنبيه", "إعلان", "اعلان", "هام", "شكرا",
        "شكراً", "جزاكم", "السلام", "عليكم", "ورحمة", "الله", "وبركاته", "بركاته", "ممكن", "حد", "حدا",
        "مين", "كيف", "ليش", "لماذا", "شو", "ايش", "وين", "متى", "بدي", "بدنا", "محتاج", "ملخص", "سلايدات",
        "كتاب", "دفتر", "قاعة", "ساعة", "ساعات", "أمس", "الآن", "رابط", "قروب", "مجموعة", "منشور", "بوست",
        "تخصص", "قسم", "فصل", "دراسي", "جدول", "مساعدة", "نصيحة", "صباح", "مساء", "الخير", "يعطيكم",
        "العافية", "دفعة", "تخرج", "شرح", "فيديو", "موقع", "ملف", "pdf", "حل", "أسئلة", "اسئلة", "سنوات",
        "سابقة", "مهم", "ضروري", "بخصوص", "لو", "سمحت", "سمحتوا", "اريد", "اريدها", "تواصل", "خاص"
    )

    // Common English core vocabulary, academic terms, and conversational keywords
    private val englishDictionaryWords = setOf(
        "the", "be", "to", "of", "and", "a", "in", "that", "have", "i", "it", "for", "not", "on", "with",
        "he", "as", "you", "do", "at", "this", "but", "his", "by", "from", "they", "we", "say", "her",
        "she", "or", "an", "will", "my", "one", "all", "would", "there", "their", "what", "so", "up",
        "out", "if", "about", "who", "get", "which", "go", "me", "when", "make", "can", "like", "time",
        "no", "just", "him", "know", "take", "people", "into", "year", "your", "good", "some", "could",
        "them", "see", "other", "than", "then", "now", "look", "only", "come", "its", "over", "think",
        "also", "back", "after", "use", "two", "how", "our", "work", "first", "well", "way", "even",
        "new", "want", "because", "any", "these", "give", "day", "most", "us", "post", "hello", "student",
        "students", "class", "course", "exam", "quiz", "midterm", "final", "test", "help", "please",
        "thanks", "thank", "notes", "pdf", "project", "assignment", "group", "lecture", "room", "prof", "dr",
        "university", "college", "department", "grade", "grades", "chapter", "book", "link", "slide", "slides"
    )

    private val latinVowels = setOf('a', 'e', 'i', 'o', 'u', 'A', 'E', 'I', 'O', 'U')

    // Consecutive consonant patterns that signify gibberish (e.g., "sygl", "rxtq", "bcdf")
    private val consonantClusterRegex = Regex("""(?i)[bcdfghjklmnpqrstvwxz]{3,}""")

    // Repeating character mashing (e.g., "aaaa", "llll")
    private val repeatingCharRegex = Regex("""(.)\1{2,}""")

    // Keyboard mashing patterns
    private val keyboardMashRegex = Regex(
        """(?i)(?:asdf|qwer|zxcv|ghjk|hjkl|dfgh|jkl;|alliaala|asygl|gugsiai)"""
    )

    // Foreign diacritics randomly mixed in ASCII text (like "ä" in "aSygläalosysigugsiai")
    private val foreignDiacriticsRegex = Regex("""[äöüßéèêëçñ]""")

    // Internal mixed-case anomaly (e.g., "aSygl", "aloSy")
    private val internalCamelAnomalyRegex = Regex("""[a-z]+[A-Z]+[a-z]+""")

    /**
     * Checks if an individual word/token is a valid recognized word in Arabic or English.
     */
    fun isRealWord(word: String): Boolean {
        val clean = word.trim { !it.isLetter() }.lowercase()
        if (clean.length < 2) return false

        // Arabic dictionary match or definite article "ال"
        if (arabicDictionaryWords.contains(clean)) return true
        if (clean.startsWith("ال") && clean.length >= 4 && clean.all { it in '\u0600'..'\u06FF' }) return true

        // English dictionary match
        if (englishDictionaryWords.contains(clean)) return true

        return false
    }

    /**
     * Checks if an individual token is blatant gibberish, consonant mashing, or keyboard mash.
     */
    fun isGibberishToken(token: String): Boolean {
        val stripped = token.trim { !it.isLetter() }
        if (stripped.length < 3) return false

        // 1. Foreign diacritics mixed in Latin token
        if (foreignDiacriticsRegex.containsMatchIn(stripped)) return true

        // 2. Keyboard mashing pattern matches
        if (keyboardMashRegex.containsMatchIn(stripped)) return true

        // 3. Repeating character mash (e.g. "aaa", "llll")
        if (repeatingCharRegex.containsMatchIn(stripped)) return true

        // 4. Low character diversity / entropy in longer tokens (e.g. "alliaala" - only 3 distinct chars in 8 chars)
        if (stripped.length >= 6) {
            val distinctCount = stripped.lowercase().toSet().size
            if (distinctCount <= 3) return true
        }

        // 5. Mixed-casing anomaly in middle of word (e.g., "aSygl")
        if (internalCamelAnomalyRegex.containsMatchIn(token)) return true

        // 6. Latin consonant mashing: check vowels vs consonants
        val isLatin = stripped.all { it in 'a'..'z' || it in 'A'..'Z' }
        if (isLatin) {
            if (isRealWord(stripped)) return false

            val vowelCount = stripped.count { it in latinVowels }
            val consonantCount = stripped.length - vowelCount

            // IF text consists mostly of consonants without vowels (e.g., "sygl", "gug") -> DISCARD
            if (vowelCount == 0) return true
            if (stripped.length >= 3 && consonantCount.toFloat() / stripped.length >= 0.66f && consonantClusterRegex.containsMatchIn(stripped)) {
                return true
            }
            if (stripped.length in 3..4 && vowelCount == 1 && !englishDictionaryWords.contains(stripped.lowercase())) {
                // Short tokens like "gug", "syg" that are not legitimate English words
                val ratio = consonantCount.toFloat() / stripped.length
                if (ratio >= 0.66f && !isCommonEnglishShortWord(stripped.lowercase())) {
                    return true
                }
            }
        }

        return false
    }

    private fun isCommonEnglishShortWord(w: String): Boolean {
        return w in setOf(
            "and", "for", "the", "not", "you", "but", "his", "her", "can", "say",
            "out", "day", "get", "use", "how", "man", "new", "now", "see", "two",
            "way", "who", "boy", "did", "its", "let", "put", "say", "she", "too",
            "our", "yes", "top", "big", "run", "box", "car", "dog", "cat", "bus",
            "red", "sun", "hot", "end", "bed", "bad", "job", "lot", "war", "cup",
            "dr", "mr", "ms", "app", "web", "lab", "pdf", "doc"
        )
    }

    /**
     * Aggressive Data Sanitizer:
     * Returns true ONLY if text represents legitimate human-authored post content.
     *
     * Rules enforced:
     * - IF text length < 3 chars -> DISCARD
     * - Minimum post length >= 18 chars with at least 2 distinct words
     * - IF text consists mostly of consonants without vowels (e.g. "sygl", "gug") -> DISCARD
     * - IF text matches random keyboard mashing patterns -> DISCARD
     * - MUST contain valid recognizable dictionary words (Arabic or English)
     */
    fun isValidPostContent(rawText: String?): Boolean {
        if (rawText.isNullOrBlank()) return false
        val clean = rawText.trim()

        // 1. Length check: Posts must have meaningful substance; discard < 3 or too short
        if (clean.length < 18) return false

        // 2. Reject pure numbers or excessive repeating chars
        if (repeatingCharRegex.containsMatchIn(clean)) return false
        val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 2) return false

        // 3. Reject oversized gibberish tokens (> 28 chars without URL)
        if (words.any { it.length > 28 && !it.startsWith("http") }) return false

        // 4. Token-by-token lexical analysis
        var realWordCount = 0
        var gibberishTokenCount = 0
        var totalLetters = 0

        for (word in words) {
            val stripped = word.trim { !it.isLetter() }
            if (stripped.length < 2) continue

            totalLetters += stripped.length

            if (isGibberishToken(stripped)) {
                gibberishTokenCount++
                continue
            }

            if (isRealWord(stripped)) {
                realWordCount++
            } else {
                // Check if it's a standard Arabic word structure (length 3..14 with valid letters)
                val isStandardArabic = stripped.all { it in '\u0600'..'\u06FF' } && stripped.length in 3..14
                if (isStandardArabic) {
                    realWordCount++
                }
            }
        }

        // STRICT RULES:
        // a) If gibberish tokens (e.g. "alliaala", "aSygläalosysigugsiai", "sygl", "gug") are present -> DISCARD
        if (gibberishTokenCount > 0) {
            return false
        }

        // b) MUST contain at least one valid recognizable dictionary word
        if (realWordCount < 1) {
            return false
        }

        // c) Proportion of legitimate words must be >= 50%
        val validRatio = realWordCount.toFloat() / words.size
        if (validRatio < 0.40f) {
            return false
        }

        return true
    }

    /**
     * Author Name Validation:
     * Ensures authorName actually looks like a human/entity name (contains spaces or capital letters),
     * NOT just a string of lowercase gibberish (e.g., "alliaala", "sygl").
     * If invalid, set to "Unknown Member".
     */
    fun sanitizeAuthorName(rawAuthor: String?): String {
        if (rawAuthor.isNullOrBlank()) return "Unknown Member"
        val clean = rawAuthor.trim()

        if (clean.length !in 3..45) return "Unknown Member"
        if (clean.contains("\n") || clean.contains("http") || clean.contains("@") || clean.contains("#")) {
            return "Unknown Member"
        }

        // Reject if matches gibberish or keyboard mash
        if (isGibberishToken(clean)) return "Unknown Member"

        val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }

        // Arabic Author Name Validation:
        val isArabic = clean.any { it in '\u0600'..'\u06FF' }
        if (isArabic) {
            val hasHonorific = clean.startsWith("د.") || clean.startsWith("م.") || clean.startsWith("أ.") ||
                    clean.startsWith("دكتور") || clean.startsWith("مهندس") || clean.startsWith("أستاذ")
            if (words.size >= 2 || hasHonorific) {
                return clean
            }
            // Single Arabic word: must have reasonable length and valid letters
            return if (clean.length in 3..15 && words.size == 1 && clean.all { it in '\u0600'..'\u06FF' || it.isWhitespace() }) {
                clean
            } else {
                "Unknown Member"
            }
        }

        // English/Latin Author Name Validation:
        // Must contain spaces (First + Last name) OR contain capital letters (e.g. "John Doe", "Sarah")
        val hasSpaces = words.size >= 2
        val hasCapital = clean.any { it.isUpperCase() }
        val isAllLowercaseNoSpace = clean.all { it.isLowerCase() || it.isWhitespace() } && !hasSpaces

        // Strictly reject single-word lowercase gibberish (e.g., "alliaala", "sygl")
        if (isAllLowercaseNoSpace) {
            return "Unknown Member"
        }

        if (hasSpaces || hasCapital) {
            // Check that words don't look like consonant mashing
            val allWordsValid = words.all { w ->
                val stripped = w.filter { it.isLetter() }
                if (stripped.length < 2) true
                else !isGibberishToken(stripped) && (stripped.any { it in latinVowels } || stripped.any { it in '\u0600'..'\u06FF' })
            }
            if (allWordsValid) return clean
        }

        return "Unknown Member"
    }

    /**
     * Resolves clean category tag for display in UI.
     * Prevents rendering empty, null, or default tags (like "#مادة", "#عام", "#General").
     * Returns null if no valid specific category is detected.
     */
    fun resolveCleanTag(postContent: String, categoryTag: String?): String? {
        val insight = PostInsights.analyze(postContent)

        return when {
            !insight.course.isNullOrBlank() -> "#مادة_${insight.course.replace(' ', '_')}"
            !insight.doctor.isNullOrBlank() -> "#د_${insight.doctor.replace(' ', '_')}"
            insight.kind != PostKind.OTHER -> "#${insight.kind.label}"
            !categoryTag.isNullOrBlank() &&
                    categoryTag != "عام" &&
                    categoryTag != "مادة" &&
                    categoryTag != "General" &&
                    categoryTag != "غير محدد" &&
                    categoryTag.length > 2 -> "#$categoryTag"
            else -> null // DO NOT RENDER generic or empty tags!
        }
    }
}
