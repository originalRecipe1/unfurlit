package io.github.originalrecipe1.unfurlit.ui.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.ui.player.AudioPlayer
import io.github.originalrecipe1.unfurlit.ui.player.VideoPlayer

@OptIn(ExperimentalFoundationApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MediaViewer(
    extraction: ExtractionResult,
    onRetry: () -> Unit,
    onViewed: () -> Unit,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
    onFullscreenChange: (Boolean) -> Unit = {},
) {
    Column(modifier = modifier) {
        MediaContent(
            extraction, onRetry, onViewed,
            modifier = if (fullscreen) Modifier.weight(1f) else Modifier.fillMaxWidth(),
            fullscreen = fullscreen,
            onFullscreenChange = onFullscreenChange,
        )
        extraction.backgroundAudio?.let { audio ->
            AudioPlayer(
                extraction = extraction,
                audio = audio,
                active = true,
                onRetry = onRetry,
                onViewed = onViewed,
                modifier = Modifier.fillMaxWidth().height(160.dp),
                compact = true,
                loop = true,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MediaContent(
    extraction: ExtractionResult,
    onRetry: () -> Unit,
    onViewed: () -> Unit,
    modifier: Modifier,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
) {
    if (extraction.media.size == 1) {
        SingleMediaViewer(
            extraction = extraction,
            media = extraction.media.single(),
            onRetry = onRetry,
            onViewed = onViewed,
            modifier = modifier,
            fullscreen = fullscreen,
            onFullscreenChange = onFullscreenChange,
        )
        return
    }

    val pagerState = rememberPagerState(pageCount = { extraction.media.size })
    Box(
        modifier = modifier.then(
            if (fullscreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth()
                    .height(GALLERY_HEIGHT)
            },
        )
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { page -> "$page:${extraction.media[page].stableKey()}" },
        ) { page ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MediaPage(
                    extraction = extraction,
                    media = extraction.media[page],
                    active = page == pagerState.currentPage,
                    onRetry = onRetry,
                    onViewed = onViewed,
                    modifier = Modifier.fillMaxSize(),
                    fullscreen = fullscreen,
                    onFullscreenChange = onFullscreenChange,
                )
            }
        }
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .semantics {
                    contentDescription =
                        "Item ${pagerState.currentPage + 1} of ${extraction.media.size}"
                },
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Text(
                text = "${pagerState.currentPage + 1} / ${extraction.media.size}",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun SingleMediaViewer(
    extraction: ExtractionResult,
    media: ExtractedMedia,
    onRetry: () -> Unit,
    onViewed: () -> Unit,
    modifier: Modifier,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
) {
    when (media) {
        is ExtractedMedia.Video -> VideoPlayer(
            extraction = extraction,
            video = media,
            onRetry = onRetry,
            onViewed = onViewed,
            modifier = modifier.fillMaxWidth(),
            fullscreen = fullscreen,
            onFullscreenChange = onFullscreenChange,
        )

        is ExtractedMedia.Image -> ZoomableImage(
            source = media.source,
            contentDescription = extraction.title ?: "Image",
            active = true,
            onRetry = onRetry,
            onViewed = onViewed,
            modifier = modifier
                .fillMaxWidth()
                .height(GALLERY_HEIGHT),
        )

        is ExtractedMedia.Audio -> AudioPlayer(
            extraction = extraction,
            audio = media,
            active = true,
            onRetry = onRetry,
            onViewed = onViewed,
            modifier = modifier
                .fillMaxWidth()
                .height(AUDIO_HEIGHT),
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MediaPage(
    extraction: ExtractionResult,
    media: ExtractedMedia,
    active: Boolean,
    onRetry: () -> Unit,
    onViewed: () -> Unit,
    modifier: Modifier,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
) {
    when (media) {
        is ExtractedMedia.Video -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            VideoPlayer(
                extraction = extraction,
                video = media,
                active = active,
                autoShowControls = false,
                onRetry = onRetry,
                onViewed = onViewed,
                modifier = Modifier.fillMaxWidth(),
                fullscreen = fullscreen,
                onFullscreenChange = onFullscreenChange,
            )
        }

        is ExtractedMedia.Image -> ZoomableImage(
            source = media.source,
            contentDescription = extraction.title ?: "Image",
            active = active,
            onRetry = onRetry,
            onViewed = onViewed,
            modifier = modifier,
        )

        is ExtractedMedia.Audio -> AudioPlayer(
            extraction = extraction,
            audio = media,
            active = active,
            onRetry = onRetry,
            onViewed = onViewed,
            modifier = modifier,
        )
    }
}

private fun ExtractedMedia.stableKey(): String = when (this) {
    is ExtractedMedia.Video -> "video:${videoSource.url}"
    is ExtractedMedia.Image -> "image:${source.url}"
    is ExtractedMedia.Audio -> "audio:${source.url}"
}

private val GALLERY_HEIGHT = 420.dp
private val AUDIO_HEIGHT = 300.dp
