package com.example

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.example.accessibility.DeepScanPostResult
import com.example.accessibility.EnhancedAccessibilityService
import com.example.data.AppDatabase
import com.example.data.ArchivedPost
import com.example.data.PostComment
import com.example.data.PostData
import com.example.data.PostRepository
import com.example.util.ArabicTextNormalizer
import com.example.util.PostInsights
import com.example.util.FuzzySearchUtility
import com.example.util.TimeFilterUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import com.example.util.PostParser
import com.example.util.PostSanitizer

sealed interface PageScanState {
    object Idle : PageScanState // STEP 1: ONE prominent button "ابدأ البحث في هذه الصفحة"
    object Loading : PageScanState // STEP 2: Loading spinner (2-3 seconds)
    object Loaded : PageScanState // STEP 3 & 4: Search input + Scrollable Content Map
}

open class NewDeepScanViewModel(application: Application) : ModifiedViewModelForUnifiedSearch(application) {

    val postRepository: PostRepository = PostRepository(AppDatabase.getDatabase(application).postDao())

    // Current interaction flow state
    private val _scanState = MutableStateFlow<PageScanState>(PageScanState.Idle)
    val scanState: StateFlow<PageScanState> = _scanState.asStateFlow()

    // EXCLUSIVE FACEBOOK GROUP MODE STATE
    private val _isSimulatedFacebookGroup = MutableStateFlow(true)
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

    // DETAILED POST VIEW ON TAP
    private val _selectedPostForDetail = MutableStateFlow<PostData?>(null)
    val selectedPostForDetail: StateFlow<PostData?> = _selectedPostForDetail.asStateFlow()

    fun selectPostForDetail(post: PostData) {
        _selectedPostForDetail.value = post
    }

    fun closePostDetail() {
        _selectedPostForDetail.value = null
    }

    // Detected Group or Page Title
    val detectedGroupName: StateFlow<String> = EnhancedAccessibilityService.detectedGroupNameFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "مجموعة دفعة الجامعة - فيسبوك"
        )

    // Total post count across all local archives
    val totalArchivedCount: StateFlow<Int> = postRepository.getTotalPostCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    // Session tracking to ensure LazyColumn keys and Compose state are unique per scan session
    private val _scanSessionId = MutableStateFlow(System.currentTimeMillis())
    val scanSessionId: StateFlow<Long> = _scanSessionId.asStateFlow()

    // In-memory list of posts currently loaded from Room DB
    protected val _rawPosts = MutableStateFlow<List<PostData>>(emptyList())
    val rawPosts: StateFlow<List<PostData>> = _rawPosts.asStateFlow()

    // Search input query
    protected val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Filtered posts displayed in the Content Map
    protected val _filteredPosts = MutableStateFlow<List<PostData>>(emptyList())
    val filteredPosts: StateFlow<List<PostData>> = _filteredPosts.asStateFlow()

    protected val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    protected val _scanProgressText = MutableStateFlow("Snapshot 1/${EnhancedAccessibilityService.MAX_SNAPSHOTS} | Saved: 0")
    val scanProgressText: StateFlow<String> = _scanProgressText.asStateFlow()

    // Saved notes sheet visibility toggle
    private val _isSavedNotebookSheetOpen = MutableStateFlow(false)
    val isSavedNotebookSheetOpen: StateFlow<Boolean> = _isSavedNotebookSheetOpen.asStateFlow()

    // Watchlist keywords for notifications
    private val _watchKeywords = MutableStateFlow<List<String>>(emptyList())
    val watchKeywords: StateFlow<List<String>> = _watchKeywords.asStateFlow()

    private val _isWatchListSheetOpen = MutableStateFlow(false)
    val isWatchListSheetOpen: StateFlow<Boolean> = _isWatchListSheetOpen.asStateFlow()

    fun openWatchListSheet() {
        _isWatchListSheetOpen.value = true
        loadWatchKeywords()
    }

    fun closeWatchListSheet() {
        _isWatchListSheetOpen.value = false
    }

    fun loadWatchKeywords() {
        _watchKeywords.value = com.example.notify.WatchList.get(getApplication())
    }

    fun addWatchKeyword(keyword: String) {
        _watchKeywords.value = com.example.notify.WatchList.add(getApplication(), keyword)
    }

    fun removeWatchKeyword(keyword: String) {
        _watchKeywords.value = com.example.notify.WatchList.remove(getApplication(), keyword)
    }

    /**
     * Checks a list of post texts against the user's WatchList keywords using ArabicTextNormalizer
     * and triggers system notifications if matches are found.
     */
    fun checkWatchListAlerts(postTexts: List<String>, groupName: String = detectedGroupName.value) {
        if (postTexts.isEmpty()) return
        try {
            com.example.notify.WatchNotifier.notifyMatches(
                getApplication(),
                postTexts,
                groupName
            )
        } catch (e: Exception) {
            android.util.Log.w("NewDeepScanViewModel", "Error checking watch matches: ${e.message}")
        }
    }

    val hasMediaProjectionPermission: StateFlow<Boolean> = com.example.capture.HybridCaptureManager.hasMediaProjectionPermission

    fun updateMediaProjectionPermission(resultCode: Int, data: android.content.Intent?) {
        com.example.capture.HybridCaptureManager.updateMediaProjectionPermission(resultCode, data)
    }

    /**
     * Resets active search filter and UI selection without deleting permanent Room DB archive.
     */
    fun clearAllCachedPosts() {
        _searchQuery.value = ""
        _statusMessage.value = null
        _selectedPostForDetail.value = null
        EnhancedAccessibilityService.clearAllCachedPosts()
    }

    /**
     * Deletes archive older than specified number of days from Room DB.
     */
    fun deleteOlderThan(days: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val count = postRepository.deleteOlderThanDays(days)
            withContext(Dispatchers.Main) {
                _statusMessage.value = "تم تنظيف $count منشور أقدم من $days يوم"
                loadStoredPostsFromDatabase(detectedGroupName.value, _searchQuery.value)
            }
        }
    }

    /**
     * Purges database archive for the currently detected group.
     */
    fun clearArchiveForCurrentGroup() {
        val group = detectedGroupName.value
        viewModelScope.launch(Dispatchers.IO) {
            postRepository.clearGroupPosts(group)
            withContext(Dispatchers.Main) {
                _rawPosts.value = emptyList()
                _filteredPosts.value = emptyList()
                _statusMessage.value = "تم تفريغ أرشيف $group محلياً"
            }
        }
    }

    // Backward-compatibility properties
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

    /**
     * Loads stored posts from Room Database for the specified group and applies any active search query.
     */
    private val scanSemaphore = Semaphore(1)
    private var dbObservationJob: Job? = null

    fun loadStoredPostsFromDatabase(group: String, currentQuery: String = "") {
        if (currentQuery.isNotBlank()) {
            searchArchive(currentQuery)
            return
        }
        dbObservationJob?.cancel()
        dbObservationJob = viewModelScope.launch(Dispatchers.IO) {
            val flow = if (group.isBlank()) postRepository.getAllPosts()
            else postRepository.getAllPostsByGroup(group)

            flow.collect { archivedList ->
                val posts = archivedList.mapNotNull { archived ->
                    val clean = archived.postContent
                    if (clean.length < 20 || PostParser.calculateConfidence(clean) < 0.60f || !PostSanitizer.isValidPostContent(clean) || PostParser.isUiNoiseOrChrome(clean)) {
                        null
                    } else {
                        val summary = PostInsights.analyze(clean).summary()
                        archived.toPostData().copy(subtitle = summary)
                    }
                }
                withContext(Dispatchers.Main) {
                    _rawPosts.value = posts
                    _filteredPosts.value = posts
                }
            }
        }
    }

    init {
        loadWatchKeywords()
        // Automatically load previously indexed posts from Room DB for this group upon startup or group detection
        viewModelScope.launch {
            detectedGroupName.collect { group ->
                loadStoredPostsFromDatabase(group, _searchQuery.value)
            }
        }
        viewModelScope.launch {
            EnhancedAccessibilityService.indexingStatusTextFlow.collect { text ->
                if (text.isNotBlank()) {
                    _scanProgressText.value = text
                }
            }
        }
    }

    /**
     * REAL CUMULATIVE INDEXING ENGINE (FAST-SCAN PRO MODE):
     * Triggered when clicking "ابدأ البحث في هذه الصفحة".
     * a) Concurrency limited via Semaphore to prevent main-thread/CPU blocking.
     * b) Triggers performFastSnapshotScan() with continuous smart-batch scrolling (5 posts/batch).
     * c) Hard limit of 50 posts prevents battery drain and stops when reaching feed bottom.
     * d) Fully executed on Dispatchers.IO, only posting UI state on Dispatchers.Main.
     */
    open fun startPageSearch() {
        if (_scanState.value is PageScanState.Loading) return
        if (!isFacebookGroupActive.value) {
            _statusMessage.value = "يرجى فتح مجموعة فيسبوك أولاً"
            return
        }

        if (!scanSemaphore.tryAcquire()) {
            return // Prevent duplicate concurrent scans
        }

        val targetGroup = detectedGroupName.value
        _scanSessionId.value = System.currentTimeMillis()
        _scanState.value = PageScanState.Loading
        _scanProgressText.value = "فحص برو السريع | دفعة 1/${EnhancedAccessibilityService.MAX_SNAPSHOTS}..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val service = EnhancedAccessibilityService.activeInstance
                var serviceMissing = false
                var serviceMsg = ""

                if (service != null) {
                    // Smart continuous batch scan (5 posts/batch, up to 50 posts max)
                    service.performFastSnapshotScan(maxSnapshots = EnhancedAccessibilityService.MAX_SNAPSHOTS) { count, batch, maxBatches ->
                        _scanProgressText.value = "فحص برو: دفعة $batch/$maxBatches | تم حفظ: $count/${EnhancedAccessibilityService.HARD_LIMIT_POSTS}"
                    }
                } else {
                    serviceMissing = true
                    val enabledInSettings = try {
                        EnhancedAccessibilityService.isServiceEnabled(getApplication<Application>())
                    } catch (e: Exception) { false }
                    serviceMsg = if (enabledInSettings)
                        "الخدمة مفعّلة لكنها لم تتصل بالتطبيق. أطفئها ثم شغلها من جديد في إعدادات إمكانية الوصول"
                    else
                        "خدمة بو لايت غير مفعّلة. افتح الإعدادات ثم إمكانية الوصول ثم بو لايت وفعّلها"
                }

                // Fetch ALL stored posts for the group from Room Database and filter clean data
                val allStoredArchived = postRepository.getPostsSnapshotByGroup(targetGroup)
                val allGroupPosts = allStoredArchived.mapNotNull { archived ->
                    val clean = archived.postContent
                    if (clean.length < 20 || PostParser.calculateConfidence(clean) < 0.60f || !PostSanitizer.isValidPostContent(clean) || PostParser.isUiNoiseOrChrome(clean)) {
                        null
                    } else {
                        archived.toPostData()
                    }
                }

                delay(60L)

                withContext(Dispatchers.Main) {
                    _rawPosts.value = allGroupPosts
                    _scanState.value = PageScanState.Loaded

                    if (_searchQuery.value.isNotBlank()) {
                        searchArchive(_searchQuery.value)
                    } else {
                        _filteredPosts.value = allGroupPosts
                    }

                    _statusMessage.value = when {
                        serviceMissing -> serviceMsg
                        allGroupPosts.isEmpty() -> "No new content found. Try scrolling manually first."
                        else -> "Done! Indexed ${allGroupPosts.size} posts total (الحد الأقصى ${EnhancedAccessibilityService.HARD_LIMIT_POSTS} منشور)."
                    }
                }
            } finally {
                scanSemaphore.release()
            }
        }
    }

    /**
     * SEARCH FUNCTIONALITY OVER ENTIRE ARCHIVE:
     * Parallel query and token-matching across Room Database archive off main thread.
     */
    open fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        searchArchive(query)
    }

    open fun onSearchQueryChanged(query: String) = onSearchQueryChange(query)

    private var searchArchiveJob: Job? = null
    protected fun searchArchive(query: String) {
        searchArchiveJob?.cancel()
        searchArchiveJob = viewModelScope.launch(Dispatchers.IO) {
            val queryTokens = ArabicTextNormalizer.tokens(query)
            val group = detectedGroupName.value

            val flow = if (group.isBlank()) postRepository.getAllPosts()
            else postRepository.getAllPostsByGroup(group)

            flow.collect { archivedList ->
                val posts = archivedList.mapNotNull { archived ->
                    val clean = archived.text.trim()
                    if (clean.length < 15 || PostParser.calculateConfidence(clean) < 0.60f || PostParser.isUiNoiseOrChrome(clean)) {
                        null
                    } else {
                        val insight = PostInsights.analyze(clean)
                        val summary = insight.summary()
                        val base = archived.toPostData().copy(subtitle = summary)

                        if (queryTokens.isEmpty()) {
                            base
                        } else {
                            val combined = clean + " " + (archived.author ?: "") + " " + PostInsights.searchTags(clean)
                            val normalizedHaystack = ArabicTextNormalizer.normalize(combined)
                            if (ArabicTextNormalizer.matchesAll(normalizedHaystack, queryTokens)) {
                                base.copy(fuzzyScore = 1.0)
                            } else {
                                null
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    _filteredPosts.value = posts
                    if (queryTokens.isEmpty()) {
                        _rawPosts.value = posts
                    }
                    _statusMessage.value = if (posts.isEmpty() && query.isNotBlank()) {
                        "لم يتم العثور على منشورات مطابقة لـ '$query' في الأرشيف المحلي"
                    } else null
                }
            }
        }
    }

    /**
     * Resets back to STEP 1: The clean trigger button "ابدأ البحث في هذه الصفحة"
     */
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

    // Helper conversion
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

    private fun generateFallbackSamplePosts(group: String): List<PostData> {
        val displayGroup = if (group.isNotBlank() && group != "صفحة غير معروفة") group else "مجموعة دفعة الجامعة - فيسبوك"
        val rawList = listOf(
            PostData(
                text = "د. خالد السعيد: نلتقي غداً الأحد في القاعة 302 لمناقشة تسليم الواجب النهائي لمقرر الرياضيات 2، يرجى الحضور في الموعد المحدد.",
                fullText = "د. خالد السعيد: نلتقي غداً الأحد في القاعة 302 لمناقشة تسليم الواجب النهائي لمقرر الرياضيات 2، يرجى الحضور في الموعد المحدد مع النسخة المطبوعة ودفتر التمارين. سيتم فتح باب المناقشة والاستفسارات لجميع المجموعات من الساعة 10 صباحاً حتى 12 ظهراً.",
                author = "د. خالد السعيد",
                timestamp = "منذ 15 دقيقة",
                groupName = displayGroup,
                category = "دكتور",
                comments = listOf(
                    PostComment(author = "أحمد خليل", text = "يا دكتور هل مسموح التسليم بصيغة PDF عبر المنصة أيضاً؟", timestamp = "منذ 10 دقائق"),
                    PostComment(author = "د. خالد السعيد", text = "نعم يا أحمد، متاح التسليم الورقي في القاعة أو الإلكتروني على المنصة.", timestamp = "منذ 5 دقائق")
                )
            ),
            PostData(
                text = "تنبيه هام للجميع: مواعيد امتحانات منتصف الفصل وجدول القاعات معلن الآن على موقع الكلية وقسم التسجيل.",
                fullText = "تنبيه هام للجميع: مواعيد امتحانات منتصف الفصل وجدول القاعات معلن الآن على موقع الكلية وقسم التسجيل. يرجى من جميع الطلبة التأكد من أرقام الجلوس وأماكن اللجان قبل موعد الاختبار بـ 24 ساعة، ولن يسمح بدخول الهواتف الذكية داخل اللجان.",
                author = "إدارة شؤون الطلاب",
                timestamp = "منذ ساعة",
                groupName = displayGroup,
                category = "تسجيل",
                comments = listOf(
                    PostComment(author = "سارة حسن", text = "هل تم نشر جدول اختبارات التخلفات أيضاً؟", timestamp = "منذ 40 دقيقة"),
                    PostComment(author = "شؤون الطلاب", text = "نعم جدول التخلفات مرفق في نهاية المنشور بالبوابة.", timestamp = "منذ 20 دقيقة")
                )
            ),
            PostData(
                text = "سلايدات ومحاضرات مادة الذكاء الاصطناعي مع حلول بنك الأسئلة للسنوات السابقة مرفوعة كاملة على رابط الدرايف التالي.",
                fullText = "سلايدات ومحاضرات مادة الذكاء الاصطناعي مع حلول بنك الأسئلة للسنوات السابقة مرفوعة كاملة على رابط الدرايف التالي. يشمل الملف ملخصات الخوارزميات، وشبكات الأعصاب الاصطناعية، وأمثلة بايثون التفاعلية المجهزة للمختبر العملي.",
                author = "م. أحمد عثمان",
                timestamp = "منذ ساعتين",
                groupName = displayGroup,
                category = "مادة",
                comments = listOf(
                    PostComment(author = "محمد عبدالسلام", text = "جزاك الله خيراً يا باشمهندس، رابط ممتاز ومفيد جداً.", timestamp = "منذ ساعة")
                )
            ),
            PostData(
                text = "إعلان تدريب صيفي مدفوع مع إمكانية التوظيف في شركة التقنية للبرمجيات. التقديم مفتوح لطلبة السنة الثالثة والرابعة.",
                fullText = "إعلان تدريب صيفي مدفوع مع إمكانية التوظيف في شركة التقنية للبرمجيات. التقديم مفتوح لطلبة السنة الثالثة والرابعة في تخصصات هندسة البرمجيات وعلوم الحاسب والذكاء الاصطناعي. فترة التدريب 3 أشهر وتتضمن مشاريع واقعية ومكافأة شهرية مجزية.",
                author = "مسؤول التوظيف والتدريب",
                timestamp = "منذ 4 ساعات",
                groupName = displayGroup,
                category = "وظيفة",
                comments = listOf(
                    PostComment(author = "مروان طارق", text = "أين يمكن إرسال السيرة الذاتية ورابط جيت هب؟", timestamp = "منذ ساعتين")
                )
            ),
            PostData(
                text = "كافتيريا الكلية تعلن عن خصم 20% لجميع الطلاب على المشروبات الساخنة والوجبات السريعة طوال فترة الامتحانات.",
                fullText = "كافتيريا الكلية تعلن عن خصم 20% لجميع الطلاب على المشروبات الساخنة والوجبات السريعة طوال فترة الامتحانات دعماً لطلابنا الأعزاء في أوقات المذاكرة.",
                author = "كافتيريا الكلية المركزية",
                timestamp = "منذ 5 ساعات",
                groupName = displayGroup,
                category = "كافتيريا"
            ),
            PostData(
                text = "هل يوجد أحد يعرف موعد فتح شعبة جديدة لمادة نظم التشغيل؟ الشعبة الحالية ممتلئة ولا أستطيع التسجيل.",
                fullText = "هل يوجد أحد يعرف موعد فتح شعبة جديدة لمادة نظم التشغيل؟ الشعبة الحالية ممتلئة تماماً ولا أستطيع إكمال الجدول، هل نتوجه لرئيس القسم لتوسيع الشعبة؟",
                author = "طالب مستجد",
                timestamp = "منذ 7 ساعات",
                groupName = displayGroup,
                category = "تسجيل",
                comments = listOf(
                    PostComment(author = "كريم سامي", text = "اجمع 10 طلاب وقدموا طلباً رسمياً لرئيس القسم وسيفتح شعبة جديدة صباح الغد.", timestamp = "منذ 6 ساعات")
                )
            ),
            PostData(
                text = "د. سارة المنصور: تم رفع تسجيل المحاضرة الخامسة لمقرر الخوارزميات وهياكل البيانات على منصة الجامعة مع ملف التمارين المحلولة.",
                fullText = "د. سارة المنصور: تم رفع تسجيل المحاضرة الخامسة لمقرر الخوارزميات وهياكل البيانات على منصة الجامعة مع ملف التمارين المحلولة. يرجى مراجعة جزئية أشجار البحث الثنائية AVL Trees قبل محاضرة الأربعاء.",
                author = "د. سارة المنصور",
                timestamp = "منذ 8 ساعات",
                groupName = displayGroup,
                category = "دكتور"
            ),
            PostData(
                text = "فرصة عمل بدوام جزئي لخريجي أو طلبة الحاسب في تصميم واجهات المستخدم UI/UX عن بعد. يرجى إرسال البورتفوليو.",
                fullText = "فرصة عمل بدوام جزئي لخريجي أو طلبة الحاسب في تصميم واجهات المستخدم UI/UX عن بعد بالعمل مع فريق دولي على تطبيقات أندرويد وويب حديثة.",
                author = "شركة إبداع للحلول الرقمية",
                timestamp = "منذ 9 ساعات",
                groupName = displayGroup,
                category = "وظيفة"
            ),
            PostData(
                text = "مكتبة الكلية: تتوفر الآن أحدث نسخ من كتب هندسة البرمجيات وقواعد البيانات للإعارة في الدور الثاني.",
                fullText = "مكتبة الكلية: تتوفر الآن أحدث نسخ من كتب هندسة البرمجيات وقواعد البيانات للإعارة في الدور الثاني، مع تخصيص قاعات هادئة للمذاكرة الجماعية.",
                author = "أمين المكتبة المركزية",
                timestamp = "منذ 10 ساعات",
                groupName = displayGroup,
                category = "مادة"
            ),
            PostData(
                text = "استفسار بخصوص امتحان الفاينل لمادة شبكات الحاسب، هل الفصل الرابع داخل ضمن المطلوب أم تم إلغاؤه؟",
                fullText = "استفسار بخصوص امتحان الفاينل لمادة شبكات الحاسب، هل الفصل الرابع داخل ضمن المطلوب أم تم إلغاؤه بناء على ما ذكره الدكتور في آخر محاضرة؟",
                author = "عمر فاروق",
                timestamp = "منذ 12 ساعة",
                groupName = displayGroup,
                category = "امتحان",
                comments = listOf(
                    PostComment(author = "ياسر إبراهيم", text = "الدكتور أكد أن الفصل الرابع مطلوب كاملاً مع مسائل الـ Subnetting.", timestamp = "منذ 11 ساعة")
                )
            ),
            PostData(
                text = "ورشة عمل مجانية عبر زوم يوم الثلاثاء القادم حول كتابة السيرة الذاتية واجتياز مقابلات التوظيف التقنية بنجاح.",
                author = "نادي الخريجين المهني",
                timestamp = "منذ 14 ساعة",
                groupName = displayGroup,
                category = "وظيفة"
            ),
            PostData(
                text = "د. محمود عبدالرحمن: الكويز الثاني سيكون يوم الخميس القادم في بداية المحاضرة، يشمل ما تم شرحه في الأسبوعين الماضيين.",
                author = "د. محمود عبدالرحمن",
                timestamp = "منذ 16 ساعة",
                groupName = displayGroup,
                category = "امتحان"
            ),
            PostData(
                text = "ملخص شامل لمقرر الأمن السيبراني والتشفير من إعداد الأوائل متاح للتحميل بصيغة PDF ومطبوع بمكتبة الأمل.",
                author = "لجنة الأنشطة الطلابية",
                timestamp = "منذ 18 ساعة",
                groupName = displayGroup,
                category = "مادة"
            ),
            PostData(
                text = "سكن الطلاب الجامعي: بدء استقبال طلبات تجديد الإقامة وتوزيع الغرف للترم الثاني ابتداءً من الأسبوع القادم.",
                author = "إدارة السكن الجامعي",
                timestamp = "أمس",
                groupName = displayGroup,
                category = "سكن"
            ),
            PostData(
                text = "مطلوب مطور Flutter وAndroid مبتدئ لمشروع تخرج تطبيقي مع مكافأة رمزية وإمكانية الاستمرار بعد التخرج.",
                author = "فريق البرمجة والتطوير",
                timestamp = "أمس",
                groupName = displayGroup,
                category = "وظيفة"
            ),
            PostData(
                text = "جدول مواعيد حافلات النقل الجامعي والخطوط الصباحية والمسائية المعدلة تزامناً مع فترات الاختبارات.",
                author = "قسم النقل والحركة",
                timestamp = "أمس",
                groupName = displayGroup,
                category = "عام"
            ),
            PostData(
                text = "د. هدى النجار: يرجى من جميع المجموعات تسليم المخطط الأولي لمشروع التخرج قبل نهاية الأسبوع الحالي.",
                author = "د. هدى النجار",
                timestamp = "منذ يومين",
                groupName = displayGroup,
                category = "دكتور"
            ),
            PostData(
                text = "تم فتح التسجيل لبطولة الشطرنج وكرة الطاولة السنوية على مستوى كليات الجامعة بالصالة الرياضية.",
                author = "رعاية الشباب والأنشطة",
                timestamp = "منذ يومين",
                groupName = displayGroup,
                category = "عام"
            ),
            PostData(
                text = "مجموعة أسئلة سنوات سابقة لمقرر هندسة النظم وتصميم البرمجيات مع مفاتيح الإجابة النموذجية.",
                author = "م. زياد كمال",
                timestamp = "منذ 3 أيام",
                groupName = displayGroup,
                category = "امتحان"
            ),
            PostData(
                text = "من يرغب في تشكيل فريق لمسابقة البرمجة الجامعية ACM ICPC؟ تبقى مكانين في الفريق.",
                author = "يوسف عبدالله",
                timestamp = "منذ 3 أيام",
                groupName = displayGroup,
                category = "عام"
            ),
            PostData(
                text = "إعلان بدء استقبال مقترحات الأبحاث لمؤتمر الطلاب السنوي في مجال الحوسبة السحابية وإنترنت الأشياء.",
                author = "عمادة البحث العلمي",
                timestamp = "منذ 4 أيام",
                groupName = displayGroup,
                category = "مادة"
            ),
            PostData(
                text = "تذكير: آخر موعد لحذف المقررات بدون رصد تقدير هو يوم الأربعاء القادم عبر البوابة الأكاديمية.",
                author = "وحدة القبول والتسجيل",
                timestamp = "منذ 4 أيام",
                groupName = displayGroup,
                category = "تسجيل"
            ),
            PostData(
                text = "د. عادل القحطاني: سيتم تعويض محاضرة الإحصاء التي تم تأجيلها يوم الإثنين القادم عبر تيمز الساعة 4 عصراً.",
                author = "د. عادل القحطاني",
                timestamp = "منذ 5 أيام",
                groupName = displayGroup,
                category = "دكتور"
            ),
            PostData(
                text = "نادي الذكاء الاصطناعي ينظم هاكاثون الابتكار البرمجي السنوي مع جوائز مالية للفرق الفائزة بالمراكز الثلاثة الأولى.",
                author = "نادي الذكاء الاصطناعي",
                timestamp = "منذ 5 أيام",
                groupName = displayGroup,
                category = "وظيفة"
            ),
            PostData(
                text = "ملزمة تجميعات امتحانات الميدتيرم لمادة الكيمياء العامة ومبادئ الفيزياء الهندسية للفرقة الإعدادية.",
                author = "مكتبة التفوق",
                timestamp = "منذ 6 أيام",
                groupName = displayGroup,
                category = "مادة"
            ),
            // OLD POSTS (> 7 DAYS) THAT MUST BE FILTERED OUT:
            PostData(
                text = "منشور قديم جداً: إرشادات الأسبوع التعريفي للعام الماضي للطلاب الجدد.",
                author = "أرشيف الكلية",
                timestamp = "منذ أسبوعين",
                groupName = displayGroup,
                category = "عام"
            ),
            PostData(
                text = "إعلان قديم: جداول امتحانات الفصل الدراسي الماضي.",
                author = "أرشيف التسجيل",
                timestamp = "منذ شهر",
                groupName = displayGroup,
                category = "تسجيل"
            )
        )

        // Requirement 2: Keep only fresh content (Today up to 7 days ago) in the result list!
        return rawList.filter { TimeFilterUtility.isWithinLast7Days(it.timestamp) }
    }

    // Compatibility methods
    fun loadContentMap(forceRefresh: Boolean = false) = startPageSearch()
    fun onDeepScanQueryChange(query: String) = onSearchQueryChange(query)
    fun triggerDeepScan(forceRefresh: Boolean = false) = startPageSearch()
    fun startMonitoringCurrentGroup() {}
    fun stopMonitoringCurrentGroup() {}
    fun dismissRadarBanner() {}
    fun saveDeepScanPostToNotebook(post: DeepScanPostResult, onDone: (() -> Unit)? = null) {
        val p = PostData(
            id = post.id,
            text = post.text,
            author = post.author,
            timestamp = post.timestamp,
            groupName = post.groupName,
            category = post.category
        )
        savePostToNotebook(p, onDone)
    }
}
