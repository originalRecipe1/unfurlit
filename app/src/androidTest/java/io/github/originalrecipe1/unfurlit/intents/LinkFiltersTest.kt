package io.github.originalrecipe1.unfurlit.intents

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originalrecipe1.unfurlit.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the manifest's VIEW filters claim post links without capturing other pages. */
class LinkFiltersTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun opensPostLinksInMainActivity() {
        val unclaimed = listOf(
            "https://www.youtube.com/watch?v=eRsGyueVLvQ",
            "https://x.com/GrimboGrim/status/1839019491835129889",
            "https://www.instagram.com/p/BqvsDleB3lV/",
            "https://www.instagram.com/reel/Chunk8-jurw/",
            "https://www.instagram.com/reels/Chunk8-jurw/",
            "https://www.instagram.com/instagram/reel/Chunk8-jurw/",
            "https://www.instagram.com/share/p/BACiUUUYQV",
            "https://www.instagram.com/share/reel/BARSSL4rTu",
            "https://vimeo.com/108650530",
            "http://vimeo.com/108650530",
            "https://vimeo.com/392479337/a52724358e",
            "https://player.vimeo.com/video/108650530",
            "https://mikf123.tumblr.com/post/172687798174/photo-post",
            "https://maskofthedragon.tumblr.com/post/626907179849564160/mona-talking-in-english",
            "https://www.tumblr.com/mikf123/172687798174",
            "https://www.pixiv.net/artworks/966412",
            "https://www.pixiv.net/en/artworks/966412",
            "https://bsky.app/profile/bsky.app/post/3l3vgf77uco2g",
            "https://benprunty.bandcamp.com/track/lanius-battle",
        ).filterNot(::opensInMainActivity)
        assertTrue("Not claimed: $unclaimed", unclaimed.isEmpty())
    }

    @Test
    fun leavesProfileSearchAndLoginPagesToOtherApps() {
        val claimed = listOf(
            "https://www.instagram.com/",
            "https://www.instagram.com/instagram/",
            "https://www.instagram.com/reels/",
            "https://www.instagram.com/explore/",
            "https://www.instagram.com/accounts/login/",
            // A photo post with a username before /p/ doesn't extract yet.
            "https://www.instagram.com/instagram/p/BqvsDleB3lV/",
            "https://vimeo.com/",
            "https://vimeo.com/channels/staffpicks",
            "https://vimeo.com/user12345",
            "https://vimeo.com/log_in",
            "https://vimeo.com/search?q=1",
            "https://player.vimeo.com/",
            "https://www.tumblr.com/",
            "https://www.tumblr.com/mikf123",
            "https://www.tumblr.com/tagged/2024",
            "https://www.tumblr.com/search/1999",
            "https://www.tumblr.com/login",
            "https://mikf123.tumblr.com/",
            "https://mikf123.tumblr.com/tagged/art",
            "https://www.pixiv.net/",
            "https://www.pixiv.net/artworks/",
            "https://www.pixiv.net/users/11",
            "https://bsky.app/",
            "https://bsky.app/profile/bsky.app",
            "https://bsky.app/profile/bsky.app/post/",
            "https://bsky.app/search?q=post",
            "https://benprunty.bandcamp.com/",
            "https://benprunty.bandcamp.com/album/fez-ost",
            "https://bandcamp.com/track/lanius-battle",
            "https://bandcamp.com/login",
        ).filter(::opensInMainActivity)
        assertTrue("Claimed: $claimed", claimed.isEmpty())
    }

    private fun opensInMainActivity(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(context.packageName)
        val handlers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return handlers.any { it.activityInfo.name == MainActivity::class.java.name }
    }
}
