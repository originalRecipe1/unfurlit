package io.github.originalrecipe1.unfurlit.ui.player

import android.view.LayoutInflater
import android.view.TextureView
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originalrecipe1.unfurlit.R
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class VideoSurfaceTest {
    @Test
    fun videoUsesAViewThatParticipatesInComposeTransitions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val view = LayoutInflater.from(context).inflate(
                R.layout.video_player, FrameLayout(context), false,
            ) as PlayerView
            assertTrue(view.videoSurfaceView is TextureView)
        }
    }
}
