package io.github.originalrecipe1.unfurlit.ui.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

/**
 * Builds a player that behaves like a media app: it takes audio focus while playing,
 * so other audio pauses or ducks, and pauses when headphones are disconnected.
 * Inactive carousel pages never request focus because they do not play.
 */
internal fun buildMediaPlayer(
    context: Context,
    contentType: @C.AudioContentType Int,
): ExoPlayer = ExoPlayer.Builder(context)
    .setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(contentType)
            .build(),
        /* handleAudioFocus = */ true,
    )
    .setHandleAudioBecomingNoisy(true)
    .build()
