package io.github.originalrecipe1.unfurlit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.originalrecipe1.unfurlit.ui.components.HistoryPager
import io.github.originalrecipe1.unfurlit.ui.home.HomeScreen
import io.github.originalrecipe1.unfurlit.ui.history.HistoryRoute
import io.github.originalrecipe1.unfurlit.ui.history.HistoryViewModel
import io.github.originalrecipe1.unfurlit.ui.theme.UnfurlitTheme
import io.github.originalrecipe1.unfurlit.ui.viewer.ViewerRoute
import io.github.originalrecipe1.unfurlit.ui.viewer.ViewerViewModel

@Composable
fun UnfurlitApp(
    unfurlitViewModel: UnfurlitViewModel,
    viewerViewModel: ViewerViewModel,
    historyViewModel: HistoryViewModel,
) {
    val destination by unfurlitViewModel.destination.collectAsStateWithLifecycle()
    val underlyingDestination = if (destination == UnfurlitDestination.History) {
        unfurlitViewModel.historyReturnDestination
    } else {
        destination
    }
    UnfurlitTheme {
        HistoryPager(
            historyVisible = destination == UnfurlitDestination.History,
            allowOpenSwipe = underlyingDestination == UnfurlitDestination.Home,
            onHistoryVisibilityChange = { visible ->
                if (visible && destination != UnfurlitDestination.History) unfurlitViewModel.showHistory()
                if (!visible && destination == UnfurlitDestination.History) unfurlitViewModel.leaveHistory()
            },
            history = {
                HistoryRoute(
                    viewModel = historyViewModel,
                    onBack = unfurlitViewModel::leaveHistory,
                    onOpen = { entry ->
                        viewerViewModel.open(entry.sourceUrl)
                        unfurlitViewModel.showViewer()
                    },
                )
            },
        ) { visible ->
            when (underlyingDestination) {
                UnfurlitDestination.Home -> HomeScreen(
                    onOpen = { url ->
                        viewerViewModel.open(url)
                        unfurlitViewModel.showViewer()
                    },
                    onShowHistory = unfurlitViewModel::showHistory,
                )

                UnfurlitDestination.Viewer -> if (visible) ViewerRoute(
                    viewModel = viewerViewModel,
                    onBack = {
                        viewerViewModel.cancel()
                        unfurlitViewModel.showHome()
                    },
                    onShowHistory = unfurlitViewModel::showHistory,
                    backEnabled = destination == UnfurlitDestination.Viewer,
                    backPreview = {
                        HomeScreen(onOpen = {}, onShowHistory = {})
                    },
                )

                UnfurlitDestination.History -> Unit
            }
        }
    }
}
