package io.github.originalrecipe1.unfurlit.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UnfurlitViewModel(
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val _destination = MutableStateFlow(savedState.destination(KEY_DESTINATION))
    val destination: StateFlow<UnfurlitDestination> = _destination.asStateFlow()
    var historyReturnDestination = savedState.destination(KEY_HISTORY_RETURN)
        private set(value) {
            field = value
            savedState[KEY_HISTORY_RETURN] = value.name
        }

    fun showHome() {
        setDestination(UnfurlitDestination.Home)
    }

    fun showViewer() {
        setDestination(UnfurlitDestination.Viewer)
    }

    fun showHistory() {
        if (_destination.value == UnfurlitDestination.History) return
        historyReturnDestination = when (_destination.value) {
            UnfurlitDestination.Viewer -> UnfurlitDestination.Viewer
            else -> UnfurlitDestination.Home
        }
        setDestination(UnfurlitDestination.History)
    }

    fun leaveHistory() {
        setDestination(historyReturnDestination)
    }

    private fun setDestination(destination: UnfurlitDestination) {
        _destination.value = destination
        savedState[KEY_DESTINATION] = destination.name
    }

    private companion object {
        const val KEY_DESTINATION = "destination"
        const val KEY_HISTORY_RETURN = "historyReturnDestination"

        fun SavedStateHandle.destination(key: String): UnfurlitDestination =
            get<String>(key)
                ?.let { name -> UnfurlitDestination.entries.firstOrNull { it.name == name } }
                ?: UnfurlitDestination.Home
    }
}

enum class UnfurlitDestination {
    Home,
    Viewer,
    History,
}
