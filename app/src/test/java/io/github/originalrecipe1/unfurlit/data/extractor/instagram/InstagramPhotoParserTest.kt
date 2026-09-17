package io.github.originalrecipe1.unfurlit.data.extractor.instagram

import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import org.junit.Assert.*
import org.junit.Test

class InstagramPhotoParserTest {
    private val page = "https://www.instagram.com/p/DXnKI92jWYQ"
    private val fixture = javaClass.getResource("/instagram/carousel.html")!!.readText()

    @Test fun extractsAllPhotosInOrderWithoutInventingSoundtrack() {
        val result = requireNotNull(InstagramPhotoParser.parse(page, page, fixture))
        assertEquals(11, result.media.size)
        result.media.forEachIndexed { i, media ->
            assertEquals("https://cdn.example/photo-$i.jpg", (media as ExtractedMedia.Image).source.url)
            assertEquals("https://www.instagram.com/", media.source.headers["Referer"])
        }
        assertEquals("example", result.author)
        assertEquals("Eleven photos", result.title)
        assertNull(result.backgroundAudio)
    }

    @Test fun ignoresUnsupportedPhotoSoundtrackWithoutLosingSlides() {
        val music = """"music_metadata":{"music_info":{"music_asset_info":{
            "progressive_download_url":"https://cdn.example/music.m4a","duration_in_ms":37000}}},"""
        val withAudio = fixture.replace("\"code\": \"DXnKI92jWYQ\"", music + "\"code\": \"DXnKI92jWYQ\"")
        val result = InstagramPhotoParser.parse(page, page, withAudio)!!
        assertEquals(11, result.media.size)
        assertNull(result.backgroundAudio)
    }

    @Test fun recognizesOnlyInstagramPostPathsAndRemovesShareParameters() {
        assertEquals(page, InstagramPhotoParser.canonicalPage("$page/?stkn=example"))
        listOf("https://instagram.com.evil.example/p/DXnKI92jWYQ", "https://www.instagram.com/reel/abc/",
            "http://www.instagram.com/p/abc/").forEach { assertNull(InstagramPhotoParser.canonicalPage(it)) }
    }

    @Test fun doesNotExtractRecommendedOrUnrelatedPosts() {
        assertNull(InstagramPhotoParser.parse(page, page, fixture.replace("DXnKI92jWYQ", "other")))
        assertNull(InstagramPhotoParser.parse(page, page, "<html>Login</html>"))
    }

    @Test fun rejectsUnsafeImagesInsteadOfDroppingSlides() {
        assertThrows(ExtractionException::class.java) {
            InstagramPhotoParser.parse(page, page, fixture.replace("https://cdn.example/photo-2.jpg", "https://127.0.0.1/image.jpg"))
        }
    }

    @Test fun leavesVideoPostsToExistingExtractor() {
        assertNull(InstagramPhotoParser.parse(page, page, fixture.replace("\"media_type\": 1", "\"media_type\": 2")))
    }

    @Test fun supportsSinglePhotoAndMixedCarousel() {
        val image = """{"media_type":1,"image_versions2":{"candidates":[{"url":"https://cdn.example/image.jpg"}]}}"""
        val video = """{"media_type":2,"video_versions":[{"url":"https://cdn.example/video.mp4"}],"video_duration":12}"""
        fun envelope(product: String) = """<script data-sjs>{"xig_polaris_media":{"if_not_gated_logged_out":$product}}</script>"""
        val single = image.replace("{\"media_type\"", "{\"code\":\"DXnKI92jWYQ\",\"media_type\"")
        assertEquals(1, InstagramPhotoParser.parse(page, page, envelope(single))!!.media.size)
        val mixed = """{"code":"DXnKI92jWYQ","carousel_media":[$image,$video]}"""
        val result = InstagramPhotoParser.parse(page, page, envelope(mixed))!!
        assertTrue(result.media[0] is ExtractedMedia.Image)
        assertEquals(12L, (result.media[1] as ExtractedMedia.Video).durationSeconds)
    }

    @Test fun rejectsOversizedPagesAndDeeplyNestedData() {
        assertThrows(ExtractionException::class.java) {
            InstagramPhotoParser.parse(page, page, " ".repeat(4 * 1024 * 1024 + 1))
        }
        assertThrows(ExtractionException::class.java) {
            InstagramPhotoParser.parse(page, page,
                "<script data-sjs>" + "{\"a\":".repeat(90) + "{}" + "}".repeat(90) + "</script>")
        }
    }
}
