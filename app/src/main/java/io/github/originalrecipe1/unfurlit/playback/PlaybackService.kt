package io.github.originalrecipe1.unfurlit.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.originalrecipe1.unfurlit.MainActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Publishes the player from [ActivePlayback] as a media session. Media3 posts the
 * playback notification and runs the service in the foreground while it plays.
 */
class PlaybackService : MediaSessionService() {
    private val scope = MainScope()
    private var session: MediaSession? = null
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            ActivePlayback.player.collect { player ->
                if (player == null) endSession() else publish(player)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping Unfurlit away from recents ends playback with the app.
        session?.player?.pause()
        endSession()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.let {
            removeSession(it)
            it.release()
        }
        session = null
        super.onDestroy()
    }

    private fun publish(player: Player) {
        val current = session
        if (current == null) {
            session = MediaSession.Builder(this, player)
                .setSessionActivity(openAppIntent())
                .setCallback(TrustedControllersOnly)
                .build()
                .also(::addSession)
        } else if (current.player !== player) {
            current.player = player
        }
    }

    private fun endSession() {
        session?.let {
            removeSession(it)
            // Releasing the session leaves the player to the screen that owns it.
            it.release()
        }
        session = null
        // A newer start (a player attached meanwhile) keeps the service running.
        stopSelf(lastStartId)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The session is exported so the system can show and control it. Other apps may
     * only connect when Android trusts them with media control, so what you watch
     * is not exposed to arbitrary installed apps.
     */
    private object TrustedControllersOnly : MediaSession.Callback {
        @androidx.annotation.OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            if (
                controller.isTrusted ||
                session.isMediaNotificationController(controller) ||
                controller.packageName == session.token.packageName
            ) {
                super.onConnect(session, controller)
            } else {
                MediaSession.ConnectionResult.reject()
            }
    }
}
