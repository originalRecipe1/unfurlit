package io.github.originalrecipe1.unfurlit.ui.components

import android.annotation.SuppressLint
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.drop
import kotlin.math.absoluteValue

/** History is the page to the right; Android retains ownership of screen-edge gestures. */
// PredictiveBackMotion.handle collects the gesture flow and handles cancellation.
@SuppressLint("NoCollectCallFound")
@Composable
fun HistoryPager(
    historyVisible: Boolean,
    allowOpenSwipe: Boolean,
    onHistoryVisibilityChange: (Boolean) -> Unit,
    history: @Composable (fullyHidden: Boolean) -> Unit,
    content: @Composable (visible: Boolean) -> Unit,
) {
    val pager = rememberPagerState(initialPage = if (historyVisible) 0 else 1) { 2 }
    val visibilityChanged by rememberUpdatedState(onHistoryVisibilityChange)
    val backMotion = remember { PredictiveBackMotion() }
    val predictingBack = backMotion.active
    val historyFullyHidden by remember {
        derivedStateOf {
            !backMotion.active && !pager.isScrollInProgress &&
                pager.currentPage == 1 && pager.currentPageOffsetFraction == 0f
        }
    }
    val rightToLeftLayout = LocalLayoutDirection.current == LayoutDirection.Rtl

    LaunchedEffect(historyVisible) {
        pager.animateScrollToPage(
            if (historyVisible) 0 else 1,
            animationSpec = tween(280, easing = FastOutSlowInEasing),
        )
    }
    LaunchedEffect(pager) {
        snapshotFlow {
            if (pager.isScrollInProgress || backMotion.active) null else pager.settledPage
        }.filterNotNull().distinctUntilChanged().drop(1).collect { page ->
            visibilityChanged(page == 0)
        }
    }

    HorizontalPager(
        state = pager,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer),
        // Keep Home's typed link and scroll position while looking at History.
        beyondViewportPageCount = 1,
        // Keep History to the right of Home, including Back previews from either edge.
        reverseLayout = !rightToLeftLayout,
        userScrollEnabled = !predictingBack && (allowOpenSwipe || historyVisible),
        flingBehavior = PagerDefaults.flingBehavior(pager, snapPositionalThreshold = 0.12f),
        pageSpacing = 12.dp,
    ) { page ->
        Box(
            modifier = Modifier.fillMaxSize().zIndex(if (page == 0) 1f else 0f).graphicsLayer {
                val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction)
                    .absoluteValue.coerceIn(0f, 1f)
                if (predictingBack) {
                    // Keep each page composed in the pager, but align both pages for the
                    // shared cross-activity animation instead of sliding the pager itself.
                    val position = pager.currentPage + pager.currentPageOffsetFraction
                    translationX = (page - position) * (size.width + 12.dp.toPx())
                } else {
                    scaleX = 1f - 0.04f * offset
                    scaleY = 1f - 0.04f * offset
                    shape = RoundedCornerShape(28.dp * (offset * 2f).coerceAtMost(1f))
                    clip = true
                    shadowElevation = 8.dp.toPx() * offset
                }
            }.predictiveBackMotion(backMotion, outgoing = page == 0),
        ) {
            if (page == 0) {
                history(!historyVisible && historyFullyHidden)
            } else {
                // Offscreen media must release its player, even though Home stays composed.
                content(predictingBack || pager.currentPage + pager.currentPageOffsetFraction > 0f)
            }
        }
    }

    // Registered after page content so this takes precedence over the viewer's back handler.
    PredictiveBackHandler(enabled = historyVisible) { events ->
        backMotion.handle(events) {
            if (backMotion.active) {
                pager.scrollToPage(1)
            } else {
                // Three-button and keyboard Back retain the regular page transition.
                pager.animateScrollToPage(1, animationSpec = tween(220))
            }
            visibilityChanged(false)
        }
    }
}
