package io.github.originalrecipe1.unfurlit.ui.components

import androidx.activity.BackEventCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Android cross-activity style motion, inspired by Harmonic-HN's
 * DefaultActivityPredictiveBackAnimation: eased 85% scale, a dimmed previous screen,
 * vertical finger tracking, and a separate spring-driven completion phase.
 * https://github.com/SimonHalvdansson/Harmonic-HN
 */
internal class PredictiveBackMotion {
    val progress = Animatable(0f)
    val completion = Animatable(0f)
    var active by mutableStateOf(false)
        private set
    var edge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
        private set
    var verticalDrag by mutableFloatStateOf(0f)
        private set

    suspend fun handle(
        events: Flow<BackEventCompat>,
        dismiss: Boolean = true,
        onBack: suspend () -> Unit,
    ) {
        var initialY: Float? = null
        try {
            events.collect { event ->
                if (initialY == null) initialY = event.touchY
                active = true
                edge = event.swipeEdge
                verticalDrag = event.touchY - initialY!!
                progress.snapTo(BackEasing.transform(event.progress.coerceIn(0f, 1f)))
            }
            if (active) {
                if (dismiss) completion.animateTo(1f, SettleSpring)
                else progress.animateTo(0f, SettleSpring)
            }
            onBack()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { progress.animateTo(0f, SettleSpring) }
            throw cancelled
        } finally {
            withContext(NonCancellable) {
                active = false
                progress.snapTo(0f)
                completion.snapTo(0f)
                verticalDrag = 0f
            }
        }
    }
}

private val BackEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
private val SettleSpring = spring<Float>(dampingRatio = 1f, stiffness = 500f)

internal fun Modifier.predictiveBackMotion(
    motion: PredictiveBackMotion,
    outgoing: Boolean,
): Modifier = graphicsLayer {
    if (motion.active) {
        val gesture = motion.progress.value
        val finish = motion.completion.value
        val remaining = 1f - finish
        val shrink = 0.15f * gesture * remaining
        scaleX = 1f - shrink
        scaleY = scaleX
        translationX = if (outgoing) {
            val edgeOffset = if (motion.edge == BackEventCompat.EDGE_LEFT) {
                (size.width * 0.075f - 8.dp.toPx()) * gesture
            } else 0f
            edgeOffset * remaining + size.width * 0.2f * finish
        } else {
            -size.width * 0.2f * remaining
        }
        translationY = if (size.height > 0f) {
            (size.height / 20f - 8.dp.toPx()).coerceAtLeast(0f) *
                (motion.verticalDrag / size.height).coerceIn(-1f, 1f) *
                (gesture * 3f).coerceAtMost(1f) * remaining
        } else 0f
        alpha = if (outgoing) remaining else 1f
        shape = RoundedCornerShape(28.dp * gesture * remaining)
        clip = true
    }
}.drawWithContent {
    drawContent()
    if (!outgoing && motion.active) {
        drawRect(Color.Black.copy(alpha = 0.25f * (1f - motion.completion.value)))
    }
}
