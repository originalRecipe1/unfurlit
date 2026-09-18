package io.github.originalrecipe1.unfurlit.ui.history

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import io.github.originalrecipe1.unfurlit.data.repository.RepositoryFactory
import io.github.originalrecipe1.unfurlit.domain.repository.HistoryRepository

class HistoryViewModel internal constructor(
    application: Application,
    private val repository: HistoryRepository,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, RepositoryFactory.historyRepository(application))

    private val generation = MutableStateFlow(0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    internal val history = generation.flatMapLatest { repository.observeHistory() }
        .map { it.withDateHeaders() }
        .cachedIn(viewModelScope)

    // Only needed when scrolling has evicted the newest pages. Keep the same
    // presenter so its existing rows remain visible until the new batch is ready.
    internal fun loadNewest() {
        generation.value += 1
    }

    fun remove(id: Long) {
        viewModelScope.launch {
            runCatching { repository.remove(id) }
                .onFailure { Log.e(TAG, "Could not remove local history entry") }
        }
    }

    fun clear() {
        viewModelScope.launch {
            runCatching { repository.clear() }
                .onFailure { Log.e(TAG, "Could not clear local history") }
        }
    }

    private companion object {
        const val TAG = "HistoryViewModel"
    }
}
