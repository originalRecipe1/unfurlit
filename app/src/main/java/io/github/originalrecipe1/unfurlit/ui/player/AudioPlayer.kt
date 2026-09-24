package io.github.originalrecipe1.unfurlit.ui.player

import android.graphics.Color
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.R

@UnstableApi
@Composable
fun AudioPlayer(
    extraction: ExtractionResult,
    audio: ExtractedMedia.Audio,
    active: Boolean,
    onRetry: () -> Unit,
    onViewed: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    loop: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(audio, extraction.title, extraction.author, loop) {
        buildMediaPlayer(context, C.AUDIO_CONTENT_TYPE_MUSIC).apply {
            setMediaSource(Media3PlaybackMapper(context).map(extraction, audio))
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            playWhenReady = active
            prepare()
        }
    }
    val artworkRequest = remember(context, audio, extraction.thumbnailUrl) {
        val artworkUrl = audio.artworkUrl ?: extraction.thumbnailUrl
        artworkUrl?.let {
            val headers = NetworkHeaders.Builder().apply {
                audio.source.headers.forEach { (name, value) -> set(name, value) }
            }.build()
            ImageRequest.Builder(context)
                .data(it)
                .httpHeaders(headers)
                .crossfade(true)
                .build()
        }
    }
    var playbackFailed by remember(audio) { mutableStateOf(false) }
    var viewReported by remember(audio) { mutableStateOf(false) }
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
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!compact) Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (artworkRequest != null) {
                    AsyncImage(
                        model = artworkRequest,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = extraction.title ?: stringResource(R.string.media_audio),
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 160.dp else 112.dp),
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        setBackgroundColor(Color.BLACK)
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        useController = true
                        controllerShowTimeoutMs = 0
                        controllerHideOnTouch = false
                        this.player = player
                        showController()
                    }
                },
                update = {
                    it.player = player
                    it.showController()
                },
                onRelease = { it.player = null },
            )
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
                    text = stringResource(R.string.playback_audio_failed),
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
