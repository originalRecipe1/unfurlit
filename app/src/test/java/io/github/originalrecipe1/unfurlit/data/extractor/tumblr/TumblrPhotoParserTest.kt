package io.github.originalrecipe1.unfurlit.data.extractor.tumblr

import io.github.originalrecipe1.unfurlit.data.network.PublicPageLoader
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TumblrPhotoParserTest {
    @Test fun canonicalizesOnlySupportedTumblrPostLinks() {
        for (url in listOf(
            "https://sample-blog.tumblr.com/post/172687798174/photo-post?source=share#notes",
            "https://sample-blog.tumblr.com/image/172687798174/",
            "https://www.tumblr.com/sample-blog/172687798174/photo-post",
            "https://tumblr.com/sample-blog/172687798174",
            "https://www.tumblr.com/blog/view/sample-blog/172687798174",
            "https://www.tumblr.com/blog/sample-blog/172687798174",
            "https://SAMPLE-BLOG.TUMBLR.COM.:443/post/172687798174",
        )) assertEquals(url, PAGE, TumblrPhotoParser.canonicalPage(url))
        for (url in listOf(
            "https://sample-blog.tumblr.com/", "https://sample-blog.tumblr.com/post/not-an-id",
            "https://www.tumblr.com/search/photos", "https://www.tumblr.com/sample-blog/tagged/photos",
            "https://sample-blog.tumblr.com.evil.example/post/172687798174",
            "https://evil.example/post/172687798174", "https://media.tumblr.com/file.gif",
            "https://www.tumblr.com@evil.example/sample-blog/172687798174",
            "https://name:secret@sample-blog.tumblr.com/post/172687798174",
            "https://www.tumblr.com:8443/sample-blog/172687798174",
            "http://sample-blog.tumblr.com/post/172687798174",
            "file:///sample-blog/172687798174", "https://www.tumblr.com/sample%2fblog/172687798174",
        )) assertNull(url, TumblrPhotoParser.canonicalPage(url))
    }

    @Test fun keepsAllFourPhotosInDisplayOrderAndTheOriginalHistoryUrl() {
        val original = "https://sample-blog.tumblr.com/post/172687798174/photo-post"
        val result = TumblrPhotoParser.parse(original, PAGE, fixture)!!
        assertEquals(original, result.sourceUrl)
        assertEquals("Tumblr", result.platform)
        assertEquals("sample-blog", result.author)
        assertEquals("Four photos", result.title)
        assertEquals("Photo order test", result.description)
        assertEquals((0..3).map { "https://cdn.example/photo-$it.png" }, urls(result.media))
        assertEquals(urls(result.media).first(), result.thumbnailUrl)
        assertNull(result.backgroundAudio)
        result.media.forEach {
            val source = (it as ExtractedMedia.Image).source
            assertEquals("image/png", source.mediaMimeType)
            assertEquals(mapOf("Referer" to "https://www.tumblr.com/"), source.headers)
        }
    }

    @Test fun rowsCanReorderBlocksAndUnlistedPhotosAreNotDropped() {
        val post = post()
        post.put("layout", JSONArray("""[{"type":"rows","display":[{"blocks":[2,0]},{"blocks":[1,4]}]}]"""))
        assertEquals(listOf(2, 0, 1, 3).map { "https://cdn.example/photo-$it.png" },
            urls(parse(post)!!.media))
    }

    @Test fun reblogImagesPrecedeTheNewPostsImages() {
        val post = post()
        post.put("trail", JSONArray().put(JSONObject().put("content", JSONArray().put(image("original")))))
        assertEquals(listOf("https://cdn.example/original.png") + (0..3).map { "https://cdn.example/photo-$it.png" },
            urls(parse(post)!!.media))
    }

    @Test fun animatedGifUsesItsMediaRatherThanTheStillPosterOrCroppedThumbnail() {
        val post = post()
        val block = JSONObject("""{"type":"image","poster":{"url":"https://cdn.example/still.png"},"media":[
          {"url":"https://cdn.example/cropped.gif","type":"image/gif","width":2000,"height":2000,"cropped":true},
          {"url":"https://cdn.example/animated.gif","type":"image/gif","width":500,"height":400,
           "poster":{"url":"https://cdn.example/poster.png","width":500,"height":400}}]}""")
        post.getJSONArray("content").put(0, block)
        val source = (parse(post)!!.media.first() as ExtractedMedia.Image).source
        assertEquals("https://cdn.example/animated.gif", source.url)
        assertEquals("image/gif", source.mediaMimeType)
    }

    @Test fun doesNotExtractOtherPostsOrProfileAndPreviewImages() {
        assertNull(parse(post().put("id", "172687798175")))
        assertNull(parse(post().put("blogName", "another-blog")))
        assertNull(parse(post().put("objectType", "blog")))
        assertNull(TumblrPhotoParser.parse(PAGE, PAGE, envelope(JSONObject().put("recommendation", post()))))
        assertNull(TumblrPhotoParser.parse(PAGE, PAGE, "<html><img src='https://cdn.example/avatar.png'></html>"))
        assertNull(TumblrPhotoParser.parse(PAGE, PAGE, "<script id='___INITIAL_STATE___'>not JSON</script>"))
    }

    @Test fun leavesVideoAudioMixedPaywalledAndUnknownContentToExistingExtractors() {
        for (type in listOf("video", "audio", "link", "paywall", "unknown")) {
            val post = post()
            post.getJSONArray("content").put(JSONObject().put("type", type))
            assertNull(type, parse(post))
            val reblog = post()
            reblog.put("trail", JSONArray().put(JSONObject().put("content",
                JSONArray().put(JSONObject().put("type", type)))))
            assertNull(type, parse(reblog))
        }
        assertNull(parse(post().put("content", JSONArray("""[{"type":"text","text":"No photos"}]""")).removeLayout()))
    }

    @Test fun rejectsUnsafeMediaWithoutSilentlyDroppingTheSlide() {
        for (url in listOf("https://127.0.0.1/photo.png", "https://192.168.1.4/photo.png",
            "http://cdn.example/photo.png", "file:///photo.png", "https://name:secret@cdn.example/photo.png")) {
            val post = post()
            post.getJSONArray("content").put(2, JSONObject().put("type", "image").put("media",
                JSONArray().put(JSONObject().put("url", url).put("type", "image/png"))))
            assertThrows(url, ExtractionException::class.java) { parse(post) }
        }
    }

    @Test fun choosesTheLargestSafeImageCandidate() {
        val post = post()
        val candidates = post.getJSONArray("content").getJSONObject(0).getJSONArray("media")
        candidates.put(JSONObject("""{"url":"https://cdn.example/large.png","type":"image/png","width":800,"height":600}"""))
        candidates.put(JSONObject("""{"url":"https://127.0.0.1/unsafe.png","type":"image/png","width":2000,"height":2000}"""))
        assertEquals("https://cdn.example/large.png", urls(parse(post)!!.media).first())
    }

    @Test fun rejectsOversizedPagesAndGalleriesAndInvalidRows() {
        assertThrows(ExtractionException::class.java) {
            TumblrPhotoParser.parse(PAGE, PAGE, " ".repeat(PublicPageLoader.MAX_PAGE_BYTES + 1))
        }
        val tooMany = post().put("content", JSONArray((0..50).map { image("photo-$it") })).removeLayout()
        assertThrows(ExtractionException::class.java) { parse(tooMany) }
        val post = post().put("layout", JSONArray("""[{"type":"rows","display":[{"blocks":[50]}]}]"""))
        assertThrows(ExtractionException::class.java) { parse(post) }
    }

    @Test fun boundsMetadataWithoutChangingTheMediaCount() {
        val post = post().put("summary", "t".repeat(600))
        post.getJSONArray("content").getJSONObject(4).put("text", "d".repeat(17_000))
        val result = parse(post)!!
        assertEquals(512, result.title!!.length)
        assertEquals(16_384, result.description!!.length)
        assertEquals(4, result.media.size)
    }

    private fun parse(post: JSONObject) = TumblrPhotoParser.parse(PAGE, PAGE,
        envelope(JSONObject().put("PeeprRoute", JSONObject().put("initialTimeline",
            JSONObject().put("objects", JSONArray().put(post))))))
    private fun post() = JSONObject(fixture.substringAfter('>').substringBefore("</script>"))
        .getJSONObject("PeeprRoute").getJSONObject("initialTimeline").getJSONArray("objects").getJSONObject(0)
    private fun JSONObject.removeLayout() = apply { remove("layout") }
    private fun image(name: String) = JSONObject().put("type", "image").put("media",
        JSONArray().put(JSONObject().put("url", "https://cdn.example/$name.png").put("type", "image/png")))
    private fun urls(media: List<ExtractedMedia>) = media.map { (it as ExtractedMedia.Image).source.url }
    private fun envelope(state: JSONObject) = "<script id='___INITIAL_STATE___' type='application/json'>$state</script>"
    private val fixture = javaClass.getResource("/tumblr/photo-post.html")!!.readText()

    private companion object {
        const val PAGE = "https://www.tumblr.com/sample-blog/172687798174"
    }
}
