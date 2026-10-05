package io.github.originalrecipe1.unfurlit.ui.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

data class PlaybackResume(
    val mediaIndex: Int,
    val positionMs: Long,
    val playWhenReady: Boolean,
)

@androidx.annotation.OptIn(UnstableApi::class)
internal fun Throwable.isHttpForbidden(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is HttpDataSource.InvalidResponseCodeException && it.responseCode == 403
    }
