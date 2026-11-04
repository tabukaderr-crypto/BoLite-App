package com.example.overlay

import androidx.compose.runtime.Composable
import com.example.ModifiedViewModelForUnifiedSearch
import com.example.data.SavedCard
import com.example.data.SavedCardRepository

@Composable
fun MiniPanel(
    allCards: List<SavedCard>,
    repository: SavedCardRepository,
    onClose: () -> Unit,
    viewModel: ModifiedViewModelForUnifiedSearch? = null
) {
    if (viewModel != null) {
        SimplifiedMiniPanelComposable(
            viewModel = viewModel,
            onClose = onClose
        )
    } else {
        val vm = androidx.lifecycle.viewmodel.compose.viewModel<com.example.MainViewModel>()
        SimplifiedMiniPanelComposable(
            viewModel = vm,
            onClose = onClose
        )
    }
}
