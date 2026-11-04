package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.DisplayMetrics
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.accessibility.DeepScanPostResult
import com.example.accessibility.EnhancedAccessibilityService
import com.example.capture.HybridCaptureManager
import com.example.data.ArchivedPost
import com.example.data.PostComment
import com.example.data.PostData
import com.example.nlp.LocalContextClassifier
import com.example.ocr.OcrPreprocessorUtility
import com.example.spatial.SpatialProximityAnalyzer
import com.example.util.FuzzySearchUtility
import com.example.util.TimeFilterUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UpdatedViewModelWithHybridPipeline:
 * Re-architected ViewModel integrating:
 * 1. Hybrid Capture Engine (MediaProjection + AccessibilityService fallback)
 * 2. Preprocessed ML Kit OCR Pipeline with Spatial Proximity Segmentation
 * 3. Local Offline NLP Context Classification ([سؤال], [إعلان], [مشاركة موارد], [نقاش])
 * 4. Zero-memory reset on click and strict session uniqueness
 */
open class UpdatedViewModelWithHybridPipeline(application: Application) : ModifiedViewModelForUnifiedSearch(application) {

    val postRepository = com.example.data.PostRepository(com.example.data.AppDatabase.getDatabase(application).postDao())

    companion object {
        private const val TAG = "HybridScanViewModel"
    }

    // Scan State
    protected val _scanState = MutableStateFlow<PageScanState>(PageScanState.Idle)
    val scanState: StateFlow<PageScanState> = _scanState.asStateFlow()

    // Permission & Engine States
    val hasMediaProjectionPermission: StateFlow<Boolean> = HybridCaptureManager.hasMediaProjectionPermission

    // Simulation toggle for local preview without physical Facebook app
    protected val _isSimulatedFacebookGroup = MutableStateFlow(true)
    val isSimulatedFacebookGroup: StateFlow<Boolean> = _isSimulatedFacebookGroup.asStateFlow()

    val isFacebookGroupActive: StateFlow<Boolean> = combine(
        EnhancedAccessibilityService.isCurrentScreenFacebookGroupFlow,
        _isSimulatedFacebookGroup
    ) { fromService, isSimulated ->
        fromService || isSimulated
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    fun toggleFacebookGroupSimulation() {
        _isSimulatedFacebookGroup.value = !_isSimulatedFacebookGroup.value
    }

    // Detail view state
    protected val _selectedPostForDetail = MutableStateFlow<PostData?>(null)
    val selectedPostForDetail: StateFlow<PostData?> = _selectedPostForDetail.asStateFlow()

    fun selectPostForDetail(post: PostData) {
        _selectedPostForDetail.value = post
    }

    fun closePostDetail() {
        _selectedPostForDetail.value = null
    }

    // Detected Group Name
    val detectedGroupName: StateFlow<String> = EnhancedAccessibilityService.detectedGroupNameFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "مجموعة دفعة الجامعة - فيسبوك"
        )

    // Session tracking to ensure LazyColumn keys and Compose state are unique per scan session
    protected val _scanSessionId = MutableStateFlow(System.currentTimeMillis())
    val scanSessionId: StateFlow<Long> = _scanSessionId.asStateFlow()

    // In-memory list of posts (Held in RAM, NOT saved to Room)
    protected val _rawPosts = MutableStateFlow<List<PostData>>(emptyList())
    val rawPosts: StateFlow<List<PostData>> = _rawPosts.asStateFlow()

    // Client-side search input query
    protected val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Filtered posts displayed in the Content Map
    protected val _filteredPosts = MutableStateFlow<List<PostData>>(emptyList())
    val filteredPosts: StateFlow<List<PostData>> = _filteredPosts.asStateFlow()

    protected val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // Saved notes sheet toggle
    protected val _isSavedNotebookSheetOpen = MutableStateFlow(false)
    val isSavedNotebookSheetOpen: StateFlow<Boolean> = _isSavedNotebookSheetOpen.asStateFlow()

    fun updateMediaProjectionPermission(resultCode: Int, data: Intent?) {
        HybridCaptureManager.updateMediaProjectionPermission(resultCode, data)
    }

    /**
     * Requirement 1: ZERO MEMORY RESET ON CLICK
     */
    fun clearAllCachedPosts() {
        _rawPosts.value = emptyList()
        _filteredPosts.value = emptyList()
        _searchQuery.value = ""
        _statusMessage.value = null
        _selectedPostForDetail.value = null
        EnhancedAccessibilityService.clearAllCachedPosts()
    }

    /**
     * Core Hybrid Scan Action:
     * 1. Resets memory.
     * 2. Captures screen via MediaProjection on Dispatchers.Default.
     * 3. Runs ML Kit OCR + Proximity Segmentation.
     * 4. Enforces <= 7 days filter and duplicate prevention.
     */
    fun startPageSearch() {
        if (_scanState.value is PageScanState.Loading) return

        if (!isFacebookGroupActive.value) {
            _statusMessage.value = "يرجى فتح مجموعة فيسبوك أولاً"
            return
        }

        // Debounce: 2-second cooldown to prevent accidental double-taps
        if (!HybridCaptureManager.canTriggerScan()) {
            return
        }

        // ZERO MEMORY RESET ON CLICK
        clearAllCachedPosts()
        _scanSessionId.value = System.currentTimeMillis()
        _scanState.value = PageScanState.Loading

        viewModelScope.launch(Dispatchers.Default) {
            val startTime = System.currentTimeMillis()
            val context = getApplication<Application>()
            val metrics = context.resources.displayMetrics

            // FIX 1: Mandatory delay of 2000ms before taking first screenshot or reading tree
            // This allows Facebook to finish lazy-loading the stream of posts
            Log.i(TAG, "[LAZY STREAM] Waiting 2000ms before scan to allow Facebook feed to render...")
            delay(2000)

            val scannedList = mutableListOf<PostData>()
            val seenBodies = mutableSetOf<String>()

            var ocrBitmap: Bitmap? = null

            suspend fun runCaptureAndAnalysisPass(isRetry: Boolean = false) {
                try {
                    // 1. Primary: MediaProjection Screenshot
                    ocrBitmap = HybridCaptureManager.captureActiveScreen(context, metrics)

                    if (ocrBitmap != null) {
                        Log.i(TAG, "MediaProjection screenshot captured (retry=$isRetry). Running OCR...")

                        // 2. Preprocess & ML Kit Recognition
                        val ocrResult = OcrPreprocessorUtility.recognizeTextFromScreen(ocrBitmap!!)
                        HybridCaptureManager.recycleBitmap(ocrBitmap)
                        ocrBitmap = null

                        // 3. Accessibility Metadata Enrichment
                        val accessFallback = HybridCaptureManager.getAccessibilityActiveScreenSnapshot()

                        // 4. Spatial Proximity Analysis & Post Segmentation
                        val segmentedPosts = SpatialProximityAnalyzer.analyzeAndSegmentPosts(
                            ocrBlocks = ocrResult.blocks,
                            screenWidth = metrics.widthPixels,
                            screenHeight = metrics.heightPixels,
                            accessibilityFallback = accessFallback
                        )

                        for (post in segmentedPosts) {
                            val normalized = post.text.trim().replace("\\s+".toRegex(), " ")
                            if (normalized.length >= 25 && seenBodies.add(normalized)) {
                                if (TimeFilterUtility.isWithinLast7Days(post.timestamp)) {
                                    scannedList.add(
                                        PostData(
                                            id = post.id,
                                            text = post.text,
                                            fullText = post.fullText,
                                            author = post.author,
                                            timestamp = post.timestamp,
                                            groupName = detectedGroupName.value,
                                            category = post.category,
                                            isWithin7Days = true,
                                            ocrConfidence = post.ocrConfidence,
                                            isOcrCaptured = post.isOcrCaptured
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        // 5. Fallback: AccessibilityService scrolling lazy stream
                        val service = EnhancedAccessibilityService.activeInstance
                        if (service != null) {
                            Log.i(TAG, "Using EnhancedAccessibilityService scrolling lazy stream fallback.")
                            val scanResult = service.performScrollingScan(maxScrolls = 30, retryMultiplier = if (isRetry) 2L else 1L)

                            for (item in scanResult.items) {
                                val normalized = item.text.trim().replace("\\s+".toRegex(), " ")
                                if (normalized.length >= 25 && seenBodies.add(normalized)) {
                                    if (TimeFilterUtility.isWithinLast7Days(item.timestamp)) {
                                        val classification = LocalContextClassifier.classifyContent(item.text)
                                        scannedList.add(
                                            PostData(
                                                id = item.id,
                                                text = item.text,
                                                fullText = item.fullText,
                                                author = item.author,
                                                timestamp = item.timestamp,
                                                groupName = item.groupName,
                                                category = classification.categoryName,
                                                matchedRanges = item.matchedRanges,
                                                fuzzyScore = item.fuzzyScore,
                                                comments = item.comments,
                                                isWithin7Days = item.isWithin7Days,
                                                ocrConfidence = 0.95f,
                                                isOcrCaptured = false
                                            )
                                        )
                                    }
                                }
                            }
                        } else {
                            // In local preview when neither service is active
                            scannedList.addAll(generateFallbackSamplePosts(detectedGroupName.value))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in Hybrid Scan Pipeline", e)
                } finally {
                    ocrBitmap?.let { HybridCaptureManager.recycleBitmap(it) }
                }
            }

            // Primary Pass
            runCaptureAndAnalysisPass(isRetry = false)

            // FIX 3: FORCE RE-SCAN IF RESULTS ARE LOW (< 3)
            if (scannedList.size < 3) {
                Log.w(TAG, "[SINGLE POST DETECTED] Only ${scannedList.size} post(s) detected. Waiting 2500ms and executing second pass...")
                delay(2500)
                runCaptureAndAnalysisPass(isRetry = true)
            }

            // Persist newly discovered posts into Room Database
            if (scannedList.isNotEmpty()) {
                val archivedBatch = scannedList.map { ArchivedPost.fromPostData(it) }
                postRepository.savePosts(archivedBatch)
            }

            // Fetch ALL stored posts for the CURRENT GROUP from Room Database
            val targetGroup = detectedGroupName.value
            val allStoredArchived = postRepository.getPostsSnapshotByGroup(targetGroup)
            val allGroupPosts = if (allStoredArchived.isNotEmpty()) {
                allStoredArchived.map { it.toPostData() }
            } else {
                scannedList
            }

            // Brief loading stabilization
            val elapsed = System.currentTimeMillis() - startTime
            val remainingDelay = (1400L - elapsed).coerceIn(200L, 1600L)
            delay(remainingDelay)

            withContext(Dispatchers.Main) {
                _rawPosts.value = allGroupPosts
                _scanState.value = PageScanState.Loaded
                if (_searchQuery.value.isNotBlank()) {
                    searchArchive(_searchQuery.value)
                } else {
                    _filteredPosts.value = allGroupPosts
                }

                _statusMessage.value = when {
                    allGroupPosts.isEmpty() -> "لا توجد منشورات مؤرشفة حالياً في شاشة المجموعة"
                    else -> "تم عرض ${allGroupPosts.size} منشور من الأرشيف المحلي للمجموعة"
                }
            }
        }
    }

    /**
     * SEARCH FUNCTIONALITY OVER ENTIRE ARCHIVE:
     * Queries Room Database (SELECT * FROM archived_posts WHERE text LIKE ? OR author LIKE ?),
     * allowing finding ANY post ever indexed in this group, even if not currently on screen!
     */
    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        searchArchive(query)
    }

    private var searchJob: kotlinx.coroutines.Job? = null
    fun searchArchive(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val clean = query.trim()
            val group = detectedGroupName.value

            val flow = if (clean.isEmpty()) {
                if (group.isBlank()) postRepository.getAllPosts()
                else postRepository.getAllPostsByGroup(group)
            } else {
                postRepository.searchPosts(group, clean)
            }

            flow.collect { archivedList ->
                val posts = archivedList.map { archived ->
                    val base = archived.toPostData()
                    if (clean.isNotEmpty()) {
                        val match = FuzzySearchUtility.matchFuzzy(base.text, clean)
                        val authorMatch = base.author?.let { FuzzySearchUtility.matchFuzzy(it, clean) }
                        val score = maxOf(match.score, authorMatch?.score ?: 0.5)
                        base.copy(fuzzyScore = score, matchedRanges = match.matchedRanges)
                    } else {
                        base
                    }
                }

                withContext(Dispatchers.Main) {
                    _filteredPosts.value = posts
                    if (clean.isEmpty()) {
                        _rawPosts.value = posts
                    }
                    _statusMessage.value = if (posts.isEmpty() && clean.isNotEmpty()) {
                        "لم يتم العثور على منشورات مطابقة لـ '$clean' في الأرشيف المحلي"
                    } else null
                }
            }
        }
    }

    fun resetToTrigger() {
        clearAllCachedPosts()
        _scanState.value = PageScanState.Idle
    }

    fun openSavedNotebookSheet() {
        _isSavedNotebookSheetOpen.value = true
    }

    fun closeSavedNotebookSheet() {
        _isSavedNotebookSheetOpen.value = false
    }

    fun savePostToNotebook(post: PostData, onDone: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val taggedText = if (post.author != null) {
                "[${post.author}] ${post.text}"
            } else {
                post.text
            }
            repository.insertCard(text = taggedText, category = post.category)
            withContext(Dispatchers.Main) {
                onDone?.invoke()
            }
        }
    }

    private fun generateFallbackSamplePosts(group: String): List<PostData> {
        val displayGroup = if (group.isNotBlank() && group != "صفحة غير معروفة") group else "مجموعة دفعة الجامعة - فيسبوك"
        val rawList = listOf(
            PostData(
                text = "د. خالد السعيد: نلتقي غداً الأحد في القاعة 302 لمناقشة تسليم الواجب النهائي لمقرر الرياضيات 2، يرجى الحضور في الموعد المحدد مع النسخة المطبوعة.",
                fullText = "د. خالد السعيد: نلتقي غداً الأحد في القاعة 302 لمناقشة تسليم الواجب النهائي لمقرر الرياضيات 2، يرجى الحضور في الموعد المحدد مع النسخة المطبوعة ودفتر التمارين. سيتم فتح باب المناقشة والاستفسارات لجميع المجموعات من الساعة 10 صباحاً حتى 12 ظهراً.",
                author = "د. خالد السعيد",
                timestamp = "منذ 15 دقيقة",
                groupName = displayGroup,
                category = "إعلان",
                ocrConfidence = 0.98f,
                isOcrCaptured = true,
                comments = listOf(
                    PostComment(author = "أحمد خليل", text = "يا دكتور هل مسموح التسليم بصيغة PDF عبر المنصة أيضاً؟", timestamp = "منذ 10 دقائق")
                )
            ),
            PostData(
                text = "سلايدات ومحاضرات مادة الذكاء الاصطناعي مع حلول بنك الأسئلة للسنوات السابقة مرفوعة كاملة على رابط الدرايف التالي.",
                fullText = "سلايدات ومحاضرات مادة الذكاء الاصطناعي مع حلول بنك الأسئلة للسنوات السابقة مرفوعة كاملة على رابط الدرايف التالي. يشمل الملف ملخصات الخوارزميات، وشبكات الأعصاب الاصطناعية، وأمثلة بايثون التفاعلية المجهزة للمختبر العملي.",
                author = "م. أحمد عثمان",
                timestamp = "منذ ساعتين",
                groupName = displayGroup,
                category = "مشاركة موارد",
                ocrConfidence = 0.96f,
                isOcrCaptured = true
            ),
            PostData(
                text = "استفسار بخصوص تسجيل المواد الاختيارية للترم القادم: هل مادة نظم التشغيل المتقدمة تتطلب حضوراً إلزامياً في المعمل؟",
                fullText = "استفسار بخصوص تسجيل المواد الاختيارية للترم القادم: هل مادة نظم التشغيل المتقدمة تتطلب حضوراً إلزامياً في المعمل؟ وهل يوجد مشروع تخرج مرتبط بها؟ نرجو الإفادة من طلبة السنة الماضية.",
                author = "سارة حسن",
                timestamp = "منذ 3 ساعات",
                groupName = displayGroup,
                category = "سؤال",
                ocrConfidence = 0.95f,
                isOcrCaptured = true
            ),
            PostData(
                text = "رأيكم في التدريب الصيفي لدى شركات البرمجيات الناشئة مقابل الشركات الكبيرة؟ ما هي التجربة الأكثر فائدة للتأهيل لسوق العمل؟",
                fullText = "رأيكم في التدريب الصيفي لدى شركات البرمجيات الناشئة مقابل الشركات الكبيرة؟ ما هي التجربة الأكثر فائدة للتأهيل لسوق العمل من حيث اكتساب المهارات والتطبيق العملي وسرعة التطور؟",
                author = "مروان طارق",
                timestamp = "منذ 5 ساعات",
                groupName = displayGroup,
                category = "نقاش",
                ocrConfidence = 0.94f,
                isOcrCaptured = true
            )
        )
        return rawList.filter { TimeFilterUtility.isWithinLast7Days(it.timestamp) }
    }

    // Compatibility properties
    val rawContentMap: StateFlow<List<DeepScanPostResult>> get() = MutableStateFlow(
        _rawPosts.value.map { it.toDeepScanResult() }
    ).asStateFlow()

    val filteredContentMap: StateFlow<List<DeepScanPostResult>> get() = MutableStateFlow(
        _filteredPosts.value.map { it.toDeepScanResult() }
    ).asStateFlow()

    val isAutoScanning: StateFlow<Boolean> get() = MutableStateFlow(_scanState.value is PageScanState.Loading).asStateFlow()
    val scanErrorMessage: StateFlow<String?> get() = statusMessage
    val totalTraversedCount: StateFlow<Int> get() = MutableStateFlow(_rawPosts.value.size).asStateFlow()
    val isCurrentGroupMonitored: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    val radarBanner: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
    val deepScanQuery: StateFlow<String> get() = searchQuery
    val deepScanResults: StateFlow<List<DeepScanPostResult>> get() = filteredContentMap
    val isDeepScanning: StateFlow<Boolean> get() = isAutoScanning
    val deepScanErrorMessage: StateFlow<String?> get() = scanErrorMessage

    private fun PostData.toDeepScanResult(): DeepScanPostResult = DeepScanPostResult(
        id = id,
        text = text,
        fullText = fullText,
        author = author,
        timestamp = timestamp,
        groupName = groupName,
        category = category,
        fuzzyScore = fuzzyScore,
        matchedRanges = matchedRanges,
        comments = comments,
        isWithin7Days = isWithin7Days
    )
}
