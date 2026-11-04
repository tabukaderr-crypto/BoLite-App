package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.accessibility.EnhancedSearchWithinPageService
import com.example.accessibility.PageSearchResult
import com.example.data.AppDatabase
import com.example.data.SavedCard
import com.example.data.SavedCardRepository
import com.example.util.CategoryMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
open class ModifiedViewModelForUnifiedSearch(application: Application) : AndroidViewModel(application) {
    val repository: SavedCardRepository

    init {
        val db = AppDatabase.getDatabase(application)
        repository = SavedCardRepository(db.savedCardDao())
    }

    // MODE A — Search Within Current Page (Primary Mode)
    private val _pageSearchQuery = MutableStateFlow("")
    val pageSearchQuery: StateFlow<String> = _pageSearchQuery.asStateFlow()

    private val _pageSearchResults = MutableStateFlow<List<PageSearchResult>>(emptyList())
    val pageSearchResults: StateFlow<List<PageSearchResult>> = _pageSearchResults.asStateFlow()

    private val _pageSourceName = MutableStateFlow("صفحة عامة")
    val pageSourceName: StateFlow<String> = _pageSourceName.asStateFlow()

    private val _isSearchingPage = MutableStateFlow(false)
    val isSearchingPage: StateFlow<Boolean> = _isSearchingPage.asStateFlow()

    private val _searchErrorMessage = MutableStateFlow<String?>(null)
    val searchErrorMessage: StateFlow<String?> = _searchErrorMessage.asStateFlow()

    private val _totalRawTextsCount = MutableStateFlow(0)
    val totalRawTextsCount: StateFlow<Int> = _totalRawTextsCount.asStateFlow()

    // MODE B — My Notebook (Secondary Tab, Room Database)
    val savedCards: StateFlow<List<SavedCard>> = repository.allCards
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val savedCount: StateFlow<Int> = repository.cardsCount
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    // Backward compatibility for MainActivity & Share-Intent
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _generatedCard = MutableStateFlow<GeneratedCard?>(null)
    val generatedCard: StateFlow<GeneratedCard?> = _generatedCard.asStateFlow()

    private val _isSavedListVisible = MutableStateFlow(false)
    val isSavedListVisible: StateFlow<Boolean> = _isSavedListVisible.asStateFlow()

    private val _feedbackMessage = MutableStateFlow<String?>(null)
    val feedbackMessage: StateFlow<String?> = _feedbackMessage.asStateFlow()

    fun onPageQueryChange(query: String) {
        _pageSearchQuery.value = query
        searchCurrentPage()
    }

    fun searchCurrentPage() {
        val service = EnhancedSearchWithinPageService.activeInstance
        if (service == null) {
            _searchErrorMessage.value = "خدمة إمكانية الوصول غير متصلة. يرجى تفعيلها من الإعدادات."
            _pageSearchResults.value = emptyList()
            return
        }

        _isSearchingPage.value = true
        _searchErrorMessage.value = null

        viewModelScope.launch(Dispatchers.Default) {
            val query = _pageSearchQuery.value
            val result = service.searchVisibleScreen(query)
            withContext(Dispatchers.Main) {
                _pageSourceName.value = result.sourceName
                _pageSearchResults.value = result.items
                _totalRawTextsCount.value = result.totalRawTextsCount
                _searchErrorMessage.value = result.errorMessage
                _isSearchingPage.value = false
            }
        }
    }

    fun saveSearchResultToNotebook(result: PageSearchResult, onDone: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertCard(text = result.text, category = result.category)
            withContext(Dispatchers.Main) {
                _feedbackMessage.value = "تم الحفظ للدفتر"
                onDone?.invoke()
            }
        }
    }

    fun deleteSavedCard(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteCard(id)
        }
    }

    fun saveQuickCard(text: String, onDone: (() -> Unit)? = null) {
        val clean = text.trim()
        if (clean.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val cat = CategoryMatcher.match(clean)
            repository.insertCard(clean, cat)
            withContext(Dispatchers.Main) {
                _feedbackMessage.value = "تم حفظ البطاقة بنجاح"
                onDone?.invoke()
            }
        }
    }

    fun updateInputText(newText: String) {
        _inputText.value = newText
    }

    fun convertToCard() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return

        val detectedCategory = CategoryMatcher.match(text)
        val titleText = if (text.length > 60) text.substring(0, 60) else text

        _generatedCard.value = GeneratedCard(
            title = titleText,
            fullText = text,
            category = detectedCategory
        )
    }

    fun saveCurrentCard() {
        val card = _generatedCard.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertCard(text = card.fullText, category = card.category)
            _generatedCard.value = null
            _inputText.value = ""
            _feedbackMessage.value = "تم حفظ البطاقة بنجاح"
        }
    }

    fun clearCurrentCard() {
        _generatedCard.value = null
    }

    fun showSavedList() {
        _isSavedListVisible.value = true
    }

    fun hideSavedList() {
        _isSavedListVisible.value = false
    }

    fun clearFeedbackMessage() {
        _feedbackMessage.value = null
    }
}
