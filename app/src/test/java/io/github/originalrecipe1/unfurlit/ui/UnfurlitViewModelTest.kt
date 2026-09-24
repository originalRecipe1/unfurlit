package io.github.originalrecipe1.unfurlit.ui

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Test

class UnfurlitViewModelTest {
    @Test
    fun repeatedHistoryNotificationsPreserveTheViewerReturnDestination() {
        val model = UnfurlitViewModel()
        model.showViewer()
        model.showHistory()
        model.showHistory()
        model.leaveHistory()
        assertEquals(UnfurlitDestination.Viewer, model.destination.value)
    }

    @Test
    fun historyOpenedFromHomeReturnsHomeAfterAnEarlierViewerVisit() {
        val model = UnfurlitViewModel()
        model.showViewer()
        model.showHistory()
        model.leaveHistory()
        model.showHome()
        model.showHistory()
        model.leaveHistory()
        assertEquals(UnfurlitDestination.Home, model.destination.value)
    }

    @Test
    fun navigationIsRestoredFromSavedState() {
        val savedState = SavedStateHandle()
        UnfurlitViewModel(savedState).apply {
            showViewer()
            showHistory()
        }
        val restored = UnfurlitViewModel(savedState)
        assertEquals(UnfurlitDestination.History, restored.destination.value)
        restored.leaveHistory()
        assertEquals(UnfurlitDestination.Viewer, restored.destination.value)
    }
}
