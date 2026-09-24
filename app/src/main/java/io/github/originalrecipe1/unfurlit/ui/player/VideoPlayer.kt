package io.github.originalrecipe1.unfurlit.ui.player

import android.graphics.Color
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.ui.PlayerView
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.R

@UnstableApi
@Composable
fun VideoPlayer(
    extraction: ExtractionResult,
    video: ExtractedMedia.Video,
    onRetry: () -> Unit,
    onViewed: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    autoShowControls: Boolean = true,
    fullscreen: Boolean = false,
    onFullscreenChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(video, extraction.title, extraction.author) {
        buildMediaPlayer(context, C.AUDIO_CONTENT_TYPE_MOVIE).apply {
            setMediaSource(Media3PlaybackMapper(context).map(extraction, video))
            playWhenReady = active
            prepare()
        }
    }
    var controlsVisible by remember(video, autoShowControls) { mutableStateOf(autoShowControls) }
    var playbackFailed by remember(video) { mutableStateOf(false) }
    var viewReported by remember(video) { mutableStateOf(false) }
    var displayAspectRatio by remember(video) { mutableStateOf<Float?>(null) }
    val currentOnViewed by rememberUpdatedState(onViewed)

    LaunchedEffect(player, active) {
        player.playWhenReady = active
        if (!active) player.pause()
    }

    DisposableEffect(player, lifecycleOwner) {
        val playerListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackFailed = true
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && !viewReported) {
                    viewReported = true
                    currentOnViewed()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                displayAspectRatio = calculateDisplayAspectRatio(
                    width = videoSize.width,
                    height = videoSize.height,
                    pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio,
                )
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        player.addListener(playerListener)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            player.removeListener(playerListener)
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }

    Box(
        modifier = modifier
            .then(
                if (fullscreen) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.aspectRatio(
                        displayAspectRatio ?: DEFAULT_VIDEO_ASPECT_RATIO,
                    )
                },
            )
            .background(androidx.compose.ui.graphics.Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                (LayoutInflater.from(viewContext).inflate(
                    R.layout.video_player, FrameLayout(viewContext), false,
                ) as PlayerView).apply {
                    setBackgroundColor(Color.BLACK)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = true
                    controllerAutoShow = autoShowControls
                    controllerShowTimeoutMs = 3_000
                    controllerHideOnTouch = true
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        controlsVisible = visibility == View.VISIBLE
                    })
                    keepScreenOn = true
                    this.player = player
                    if (!autoShowControls) hideController()
                }
            },
            update = {
                it.controllerAutoShow = autoShowControls
                it.player = player
                // Clear controls on the outgoing page so swiping back stays unobstructed.
                if (!active && !autoShowControls) it.hideController()
            },
            onRelease = {
                it.visibility = View.INVISIBLE
                it.keepScreenOn = false
                it.setControllerVisibilityListener(null as PlayerView.ControllerVisibilityListener?)
                it.player = null
            },
        )

        if (controlsVisible) Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.58f),
            contentColor = androidx.compose.ui.graphics.Color.White,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            IconButton(onClick = { onFullscreenChange(!fullscreen) }) {
                Icon(
                    painter = painterResource(
                        if (fullscreen) {
                            R.drawable.ic_fullscreen_exit
                        } else {
                            R.drawable.ic_fullscreen
                        },
                    ),
                    contentDescription = if (fullscreen) {
                        stringResource(R.string.exit_fullscreen)
                    } else {
                        stringResource(R.string.enter_fullscreen)
                    },
                )
            }
        }

        if (playbackFailed) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(20.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(
                    12.dp,
                    Alignment.CenterVertically,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.playback_video_failed),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(
                    onClick = onRetry,
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Text(stringResource(R.string.action_extract_again))
                }
            }
        }
    }
}

internal fun calculateDisplayAspectRatio(
    width: Int,
    height: Int,
    pixelWidthHeightRatio: Float,
): Float? {
    if (width <= 0 || height <= 0 || pixelWidthHeightRatio <= 0f) return null

    val displayRatio = width.toFloat() * pixelWidthHeightRatio / height.toFloat()
    return displayRatio.takeIf {
        it.isFinite() && it in MIN_VIDEO_ASPECT_RATIO..MAX_VIDEO_ASPECT_RATIO
    }
}

private const val DEFAULT_VIDEO_ASPECT_RATIO = 16f / 9f
private const val MIN_VIDEO_ASPECT_RATIO = 0.1f
private const val MAX_VIDEO_ASPECT_RATIO = 10f
