package io.github.originalrecipe1.unfurlit.ui.components

import android.annotation.SuppressLint
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

/** Keeps the current screen alive until Back commits, including during cancellation. */
// PredictiveBackMotion.handle collects the gesture flow and handles cancellation.
@SuppressLint("NoCollectCallFound")
@Composable
internal fun PredictiveBackSurface(
    onBack: () -> Unit,
    dismissOnBack: Boolean = true,
    enabled: Boolean = true,
    preview: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val motion = remember { PredictiveBackMotion() }
    var committed by remember { mutableStateOf(false) }
    val currentOnBack by rememberUpdatedState(onBack)
    val dismiss by rememberUpdatedState(dismissOnBack)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (motion.active || committed) {
            Box(Modifier.fillMaxSize().predictiveBackMotion(motion, outgoing = false)) {
                preview()
            }
        }
        if (!committed) Box(Modifier.fillMaxSize().predictiveBackMotion(motion, outgoing = true)) {
            content()
        }
    }

    PredictiveBackHandler(enabled = enabled && !committed) { events ->
        motion.handle(events, dismiss) {
            // Keep the finished screen removed even if navigation is applied next frame.
            // Fullscreen Back and cancellation keep the current player alive.
            if (dismiss && motion.active) committed = true
            currentOnBack()
        }
    }
}
