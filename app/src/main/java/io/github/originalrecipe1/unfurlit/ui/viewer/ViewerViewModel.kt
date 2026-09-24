package io.github.originalrecipe1.unfurlit.ui.viewer

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import io.github.originalrecipe1.unfurlit.data.repository.RepositoryFactory
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult

class ViewerViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    private val repository = RepositoryFactory.mediaRepository(application)
    private val historyRepository = RepositoryFactory.historyRepository(application)
    private val _state = MutableStateFlow<ViewerState>(ViewerState.Idle)
    val state: StateFlow<ViewerState> = _state.asStateFlow()
    private var extractionJob: Job? = null
    private var recordedResult: ExtractionResult? = null

    init {
        // Stream URLs expire, so a link restored after process death is extracted again.
        savedState.get<String>(KEY_SOURCE_URL)?.let(::open)
    }

    fun retry() {
        state.value.sourceUrl.takeIf(String::isNotBlank)?.let(::open)
    }

    fun open(url: String) {
        extractionJob?.cancel()
        recordedResult = null
        savedState[KEY_SOURCE_URL] = url
        extractionJob = viewModelScope.launch {
            _state.value = ViewerState.Loading(url)
            _state.value = try {
                val result = repository.open(url)
                if (result.media.isEmpty()) {
                    throw ExtractionException(ExtractionError.MediaUnavailable)
                }
                ViewerState.Ready(result)
            } catch (error: ExtractionException) {
                ViewerState.Failed(url, error.error)
            }
        }
    }

    fun recordView(result: ExtractionResult) {
        val currentResult = (state.value as? ViewerState.Ready)?.extraction
        if (currentResult != result || recordedResult == result) return
        recordedResult = result
        viewModelScope.launch {
            runCatching { historyRepository.recordView(result) }
                .onFailure {
                    if (recordedResult == result) recordedResult = null
                    Log.e(TAG, "Could not save local history entry")
                }
        }
    }

    fun cancel() {
        extractionJob?.cancel()
        extractionJob = null
        recordedResult = null
        savedState.remove<String>(KEY_SOURCE_URL)
        _state.value = ViewerState.Idle
    }

    companion object {
        private const val TAG = "ViewerViewModel"
        private const val KEY_SOURCE_URL = "sourceUrl"
    }
}

sealed interface ViewerState {
    val sourceUrl: String

    data object Idle : ViewerState {
        override val sourceUrl: String = ""
    }

    data class Loading(override val sourceUrl: String) : ViewerState

    data class Ready(
        val extraction: ExtractionResult,
    ) : ViewerState {
        override val sourceUrl: String = extraction.sourceUrl
    }

    data class Failed(
        override val sourceUrl: String,
        val error: ExtractionError,
    ) : ViewerState
}
