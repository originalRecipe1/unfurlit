package io.github.originalrecipe1.unfurlit.data.extractor.tiktok

import org.junit.Assert.assertEquals
import org.junit.Test

class TikTokLinksTest {
    @Test
    fun messengerShareLinkUsesTheNumericShareItemId() {
        assertEquals(
            "https://www.tiktok.com/@4ourweeks/video/7711390537470725390",
            TikTokLinks.normalize(
                "https://www.tiktok.com/@4ourweeks/video/e1ml5fsz9al5hq?_r=1&_t=ZT-91abcdEFgh" +
                    "&share_item_id=7711390537470725390&source=h5_m&u_code=abc123",
            ),
        )
    }

    @Test
    fun photoPostsAndOtherTikTokHostsAreRewritten() {
        assertEquals(
            "https://www.tiktok.com/@example/photo/7273914709152222496",
            TikTokLinks.normalize("https://m.tiktok.com/@example/photo/x9k2ab?share_item_id=7273914709152222496"),
        )
        assertEquals(
            "https://www.tiktok.com/@example/video/123",
            TikTokLinks.normalize("http://tiktok.com/@example/video/abc/?share_item_id=123"),
        )
    }

    @Test
    fun otherLinksAreUnchanged() {
        listOf(
            // Already numeric.
            "https://www.tiktok.com/@example/video/7711390537470725390?share_item_id=1",
            // No usable share_item_id.
            "https://www.tiktok.com/@example/video/e1ml5fsz9al5hq?_r=1",
            "https://www.tiktok.com/@example/video/e1ml5fsz9al5hq?share_item_id=",
            "https://www.tiktok.com/@example/video/e1ml5fsz9al5hq?share_item_id=12a4",
            "https://www.tiktok.com/@example/video/e1ml5fsz9al5hq?share_item_id=12345678901234567890123456",
            "https://www.tiktok.com/@example/video/e1ml5fsz9al5hq?xshare_item_id=123",
            // Not a post.
            "https://www.tiktok.com/@example?share_item_id=123",
            "https://www.tiktok.com/t/ZPNh7mAqB/?share_item_id=123",
            // Not TikTok.
            "https://www.tiktok.com.evil.example/@example/video/abc?share_item_id=123",
            "https://evil.example/@example/video/abc?share_item_id=123",
            "not a url",
        ).forEach { assertEquals(it, TikTokLinks.normalize(it)) }
    }
}
