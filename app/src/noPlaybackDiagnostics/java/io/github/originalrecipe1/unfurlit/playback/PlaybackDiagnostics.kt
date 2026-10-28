package io.github.originalrecipe1.unfurlit.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer

/** Release builds time nothing; the debug version is in src/playbackDiagnostics. */
@androidx.annotation.OptIn(UnstableApi::class)
object PlaybackDiagnostics {
    fun formatSelector(default: String): String = default

    fun extractionFinished() = Unit

    @Suppress("UNUSED_PARAMETER")
    fun watch(player: ExoPlayer): TransferListener? = null
}
