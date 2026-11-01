package io.github.originalrecipe1.unfurlit.data.extractor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsupportedSitesTest {
    @Test
    fun rejectsRedditPagesShortLinksAndMediaHosts() {
        listOf(
            "https://www.reddit.com/r/videos/comments/6rrwyj/that_small_heart_attack/",
            "https://reddit.com/comments/15ko0qu",
            "https://old.reddit.com/r/RobloxArt/comments/15ko0qu/",
            "https://www.reddit.com/media?url=https%3A%2F%2Fi.redd.it%2Fppt5yciyipgb1.jpg",
            "https://REDDIT.COM./gallery/hrrh23",
            "https://redd.it/hrrh23",
            "https://i.redd.it/upjtjcx2npzz.jpg",
            "https://v.redd.it/abc123",
            "https://preview.redd.it/00af44lpn0u51.jpg?width=960",
            "https://external-preview.redd.it/x.jpg",
            "https://i.redditmedia.com/x.jpg",
        ).forEach { assertTrue(it, UnsupportedSites.matches(it)) }
    }

    @Test
    fun leavesLookalikesAndOtherSitesAlone() {
        listOf(
            "https://notreddit.com/r/x/comments/1/",
            "https://reddit.com.example/r/x/comments/1/",
            "https://eddrit.com/r/RobloxArt/comments/15ko0qu/",
            "https://example.com/?u=https://www.reddit.com/",
            "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
            "not a url",
        ).forEach { assertFalse(it, UnsupportedSites.matches(it)) }
    }
}
