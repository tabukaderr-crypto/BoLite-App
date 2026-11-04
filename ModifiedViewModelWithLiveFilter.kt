package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.accessibility.EnhancedAccessibilityService
import com.example.data.AppDatabase
import com.example.data.SavedCard
import com.example.data.SavedCardRepository
import com.example.util.CategoryMatcher
import com.example.util.ParsedPostBlock
import com.example.util.SearchScorer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GeneratedCard(
    val title: String,
    val fullText: String,
    val category: String
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
open class ModifiedViewModelWithLiveFilter(application: Application) : AndroidViewModel(application) {
    val repository: SavedCardRepository

    init {
        val db = AppDatabase.getDatabase(application)
        repository = SavedCardRepository(db.savedCardDao())
    }

    // 1. Live Screen Filter state (Page Filter Lens over Facebook/WhatsApp)
    private val _liveFilteredBlocks = MutableStateFlow<List<ParsedPostBlock>>(emptyList())
    val liveFilteredBlocks: StateFlow<List<ParsedPostBlock>> = _liveFilteredBlocks.asStateFlow()

    private val _livePageSource = MutableStateFlow("صفحة عامة")
    val livePageSource: StateFlow<String> = _livePageSource.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // 2. Saved Room cards (Daftari / Notebook)
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

    // 3. Manual search query (Secondary mode: searches saved Room cards only)
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val searchResults: StateFlow<List<SavedCard>> = _searchQuery
        .debounce(300L)
        .distinctUntilChanged()
        .combine(repository.allCards) { query, cards ->
            if (query.isBlank()) {
                emptyList()
            } else {
                SearchScorer.scoreAndRank(cards, query)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // 4. Main screen & Share-Intent state (Backward compatibility preserved)
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _generatedCard = MutableStateFlow<GeneratedCard?>(null)
    val generatedCard: StateFlow<GeneratedCard?> = _generatedCard.asStateFlow()

    private val _isSavedListVisible = MutableStateFlow(false)
    val isSavedListVisible: StateFlow<Boolean> = _isSavedListVisible.asStateFlow()

    private val _feedbackMessage = MutableStateFlow<String?>(null)
    val feedbackMessage: StateFlow<String?> = _feedbackMessage.asStateFlow()

    fun scanLiveScreen() {
        val service = EnhancedAccessibilityService.activeInstance ?: return
        _isScanning.value = true

        viewModelScope.launch(Dispatchers.Default) {
            val (source, blocks) = service.parseLiveScreen()
            withContext(Dispatchers.Main) {
                _livePageSource.value = source
                _liveFilteredBlocks.value = blocks
                _isScanning.value = false
            }
        }
    }

    fun saveBlockToNotebook(block: ParsedPostBlock, onSaved: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertCard(text = block.text, category = block.category)
            withContext(Dispatchers.Main) {
                _feedbackMessage.value = "تم الحفظ للدفتر"
                onSaved?.invoke()
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
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

    fun clearCurrentCard() {
        _generatedCard.value = null
    }

    fun deleteSavedCard(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteCard(id)
        }
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
