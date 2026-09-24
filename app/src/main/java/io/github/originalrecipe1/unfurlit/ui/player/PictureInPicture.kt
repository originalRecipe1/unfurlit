package io.github.originalrecipe1.unfurlit.ui.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import io.github.originalrecipe1.unfurlit.R
import kotlin.math.roundToInt

/** Null outside [io.github.originalrecipe1.unfurlit.MainActivity], e.g. in screen tests. */
val LocalPictureInPicture = staticCompositionLocalOf<PictureInPictureController?> { null }

/**
 * Lets the playing video continue in a picture-in-picture window. The active video
 * attaches its player; while it plays, leaving the app (Home, recents, or the PiP
 * button) shrinks the viewer into a window with a play/pause action.
 */
class PictureInPictureController(private val activity: Activity) {
    var inPictureInPicture by mutableStateOf(false)
        private set

    val supported: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private var player: Player? = null
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateParams()
        override fun onVideoSizeChanged(videoSize: VideoSize) = updateParams()
    }
    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val player = player ?: return
            if (player.isPlaying) player.pause() else player.play()
        }
    }

    /** Whether leaving the app now would continue the video in a window. */
    private val eligible: Boolean
        get() = supported && player?.isPlaying == true

    fun attach(player: Player) {
        if (this.player === player) return
        this.player?.removeListener(playerListener)
        this.player = player
        player.addListener(playerListener)
        updateParams()
    }

    fun detach(player: Player) {
        if (this.player !== player) return
        player.removeListener(playerListener)
        this.player = null
        updateParams()
    }

    /** Enters picture-in-picture from the player's button. */
    fun enter() {
        if (!supported || player == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { activity.enterPictureInPictureMode(buildParams()) }
    }

    fun onCreate() {
        if (!supported) return
        ContextCompat.registerReceiver(
            activity,
            actionReceiver,
            IntentFilter(ACTION_TOGGLE_PLAYBACK),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun onDestroy() {
        if (supported) activity.unregisterReceiver(actionReceiver)
        player?.removeListener(playerListener)
        player = null
    }

    fun onPictureInPictureModeChanged(inPictureInPicture: Boolean) {
        this.inPictureInPicture = inPictureInPicture
    }

    /** Android 8–11 has no auto-enter, so the viewer enters when the user leaves the app. */
    fun onUserLeaveHint() {
        if (eligible && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) enter()
    }

    private fun updateParams() {
        if (!supported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Only the window's own params change here; this never enters PiP by itself.
        runCatching { activity.setPictureInPictureParams(buildParams()) }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildParams(): PictureInPictureParams {
        val player = player
        val builder = PictureInPictureParams.Builder()
        player?.videoSize?.let { size ->
            pictureInPictureAspectRatio(size.width, size.height, size.pixelWidthHeightRatio)
                ?.let { (width, height) -> builder.setAspectRatio(Rational(width, height)) }
        }
        builder.setActions(listOfNotNull(player?.let { playbackAction(it.isPlaying) }))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(eligible)
            builder.setSeamlessResizeEnabled(false)
        }
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun playbackAction(playing: Boolean): RemoteAction {
        val title = activity.getString(if (playing) R.string.pause else R.string.play)
        return RemoteAction(
            Icon.createWithResource(activity, if (playing) R.drawable.ic_pause else R.drawable.ic_play),
            title,
            title,
            PendingIntent.getBroadcast(
                activity,
                0,
                Intent(ACTION_TOGGLE_PLAYBACK).setPackage(activity.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    private companion object {
        const val ACTION_TOGGLE_PLAYBACK =
            "io.github.originalrecipe1.unfurlit.action.TOGGLE_PIP_PLAYBACK"
    }
}

/**
 * The window aspect ratio for a video, as a width/height pair, clamped to the range
 * Android accepts for picture-in-picture (about 1:2.39 to 2.39:1). Null while unknown.
 */
internal fun pictureInPictureAspectRatio(
    width: Int,
    height: Int,
    pixelWidthHeightRatio: Float,
): Pair<Int, Int>? {
    val ratio = calculateDisplayAspectRatio(width, height, pixelWidthHeightRatio) ?: return null
    val clamped = ratio.coerceIn(MIN_PIP_ASPECT_RATIO, MAX_PIP_ASPECT_RATIO)
    return (clamped * PIP_RATIO_DENOMINATOR).roundToInt() to PIP_RATIO_DENOMINATOR
}

private const val PIP_RATIO_DENOMINATOR = 10_000
// Slightly inside the platform's 100:239 and 239:100 limits to absorb rounding.
private const val MIN_PIP_ASPECT_RATIO = 0.4185f
private const val MAX_PIP_ASPECT_RATIO = 2.389f
