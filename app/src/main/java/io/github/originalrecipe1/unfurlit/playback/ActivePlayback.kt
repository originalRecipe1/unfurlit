package io.github.originalrecipe1.unfurlit.playback

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hands the on-screen player to [PlaybackService], which publishes it as a media
 * session so it can keep playing in the background with notification, lock-screen
 * and headset controls. The composable that created the player still owns and
 * releases it; the session never outlives it.
 */
object ActivePlayback {
    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()

    fun attach(context: Context, player: Player) {
        _player.value = player
        // Called from composition, so the app is in the foreground and may start it.
        runCatching {
            context.startService(Intent(context, PlaybackService::class.java))
        }.onFailure { Log.w(TAG, "Could not start the playback service") }
    }

    fun detach(player: Player) {
        _player.compareAndSet(player, null)
    }

    private const val TAG = "ActivePlayback"
}
