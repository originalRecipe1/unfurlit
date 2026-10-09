package io.github.originalrecipe1.unfurlit.data.extractor.tumblr

import io.github.originalrecipe1.unfurlit.data.network.PublicPageLoader
import io.github.originalrecipe1.unfurlit.data.network.UnsafeNetworkTargetException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.domain.model.StreamFormat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TumblrPostParserTest {
    @Test fun canonicalizesOnlySupportedTumblrPostLinks() {
        for (url in listOf(
            "https://sample-blog.tumblr.com/post/172687798174/photo-post?source=share#notes",
            "https://sample-blog.tumblr.com/image/172687798174/",
            "https://www.tumblr.com/sample-blog/172687798174/photo-post",
            "https://tumblr.com/sample-blog/172687798174",
            "https://www.tumblr.com/blog/view/sample-blog/172687798174",
            "https://www.tumblr.com/blog/sample-blog/172687798174",
            "https://SAMPLE-BLOG.TUMBLR.COM.:443/post/172687798174",
        )) assertEquals(url, PAGE, TumblrPostParser.canonicalPage(url))
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
        )) assertNull(url, TumblrPostParser.canonicalPage(url))
    }

    @Test fun keepsAllFourPhotosInDisplayOrderAndTheOriginalHistoryUrl() {
        val original = "https://sample-blog.tumblr.com/post/172687798174/photo-post"
        val result = TumblrPostParser.parse(original, PAGE, fixture)!!
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
        assertNull(TumblrPostParser.parse(PAGE, PAGE, envelope(JSONObject().put("recommendation", post()))))
        assertNull(TumblrPostParser.parse(PAGE, PAGE, "<html><img src='https://cdn.example/avatar.png'></html>"))
        assertNull(TumblrPostParser.parse(PAGE, PAGE, "<script id='___INITIAL_STATE___'>not JSON</script>"))
    }

    @Test fun nativeVideoUsesTheMp4WithMuxedAudioAndPosterOnlyAsThumbnail() {
        val post = post().put("content", JSONArray().put(video())).removeLayout()
        val result = parse(post)!!
        val media = result.media.single() as ExtractedMedia.Video
        assertEquals("https://va.media.tumblr.com/native.mp4", media.videoSource.url)
        assertEquals(StreamFormat.Progressive, media.videoSource.format)
        assertEquals("video/mp4", media.videoSource.mediaMimeType)
        assertEquals(mapOf("Referer" to "https://www.tumblr.com/"), media.videoSource.headers)
        assertTrue(media.videoSource.cookies.isEmpty())
        assertNull(media.audioSource)
        assertEquals(7L, media.durationSeconds)
        assertEquals("https://64.media.tumblr.com/poster.jpg", result.thumbnailUrl)
        assertNull(result.backgroundAudio)
    }

    @Test fun mixedPhotosAndNativeVideosRespectRowsAndReblogOrder() {
        val post = post().put("content", JSONArray().put(image("after")).put(video()))
            .put("layout", JSONArray("""[{"type":"rows","display":[{"blocks":[1,0]}]}]"""))
            .put("trail", JSONArray().put(JSONObject().put("content", JSONArray().put(image("before")))))
        val result = parse(post)!!
        assertEquals(listOf("https://cdn.example/before.png", "https://va.media.tumblr.com/native.mp4",
            "https://cdn.example/after.png"), urls(result.media))
        assertEquals(listOf(ExtractedMedia.Image::class, ExtractedMedia.Video::class, ExtractedMedia.Image::class),
            result.media.map { it::class })
        assertEquals("https://cdn.example/before.png", result.thumbnailUrl)
    }

    @Test fun videoHistoryKeepsEverySupportedOriginalUrl() {
        val html = envelope(JSONObject().put("PeeprRoute", JSONObject().put("initialTimeline",
            JSONObject().put("objects", JSONArray().put(post().put("content", JSONArray().put(video())).removeLayout())))))
        for (url in listOf("https://sample-blog.tumblr.com/post/172687798174/video?source=share",
            PAGE, "https://www.tumblr.com/blog/view/sample-blog/172687798174")) {
            val result = TumblrPostParser.parse(url, TumblrPostParser.canonicalPage(url)!!, html)!!
            assertEquals(url, result.sourceUrl)
            assertTrue(result.media.single() is ExtractedMedia.Video)
        }
    }

    @Test fun optionalVideoDurationAndPosterNeverBecomeMedia() {
        val video = video().apply { remove("duration"); remove("poster") }
        val post = post().put("content", JSONArray().put(video)).removeLayout()
        val result = parse(post)!!
        assertNull((result.media.single() as ExtractedMedia.Video).durationSeconds)
        assertNull(result.thumbnailUrl)
        video.put("duration", -1).put("poster", JSONArray("""[
          {"url":"https://127.0.0.1/still.jpg","type":"image/jpeg"}]"""))
        assertNull((parse(post)!!.media.single() as ExtractedMedia.Video).durationSeconds)
        assertNull(parse(post)!!.thumbnailUrl)
    }

    @Test fun externalOrIncompleteVideoLeavesTheEntireMixedPostToExistingExtractors() {
        val unsupported = listOf(video().put("provider", "youtube"), video().apply { remove("provider") },
            video().apply { remove("media") }, video().apply { getJSONObject("media").remove("url") },
            video().apply { getJSONObject("media").put("type", "application/x-mpegURL") })
        for (block in unsupported) {
            val post = post()
            post.getJSONArray("content").put(block)
            assertNull(parse(post))
            val reblog = post().put("trail", JSONArray().put(JSONObject().put("content", JSONArray().put(block))))
            assertNull(parse(reblog))
        }
    }

    @Test fun rejectsUnsafeNativeVideoWithoutUsingTheEmbedUrlOrFallingBack() {
        for (url in listOf("https://127.0.0.1/video.mp4", "https://[::1]/video.mp4",
            "http://va.media.tumblr.com/video.mp4", "https://name:secret@va.media.tumblr.com/video.mp4",
            "file:///video.mp4")) {
            val video = video().apply { getJSONObject("media").put("url", url) }
            val post = post().put("content", JSONArray().put(video)).removeLayout()
            val failure = assertThrows(url, ExtractionException::class.java) { parse(post) }
            assertTrue(failure.cause is UnsafeNetworkTargetException)
        }
    }

    @Test fun leavesAudioPaywalledAndUnknownContentToExistingExtractors() {
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
            val failure = assertThrows(url, ExtractionException::class.java) { parse(post) }
            assertTrue(failure.cause is UnsafeNetworkTargetException)
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
            TumblrPostParser.parse(PAGE, PAGE, " ".repeat(PublicPageLoader.MAX_PAGE_BYTES + 1))
        }
        val tooMany = post().put("content", JSONArray((0..50).map { image("photo-$it") })).removeLayout()
        assertThrows(ExtractionException::class.java) { parse(tooMany) }
        val mixed = post().put("content", JSONArray((0..49).map { image("photo-$it") }).put(video())).removeLayout()
        assertThrows(ExtractionException::class.java) { parse(mixed) }
        val longTrail = post().put("trail", JSONArray((0..50).map { JSONObject().put("content", JSONArray()) }))
        assertThrows(ExtractionException::class.java) { parse(longTrail) }
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

    private fun parse(post: JSONObject) = TumblrPostParser.parse(PAGE, PAGE,
        envelope(JSONObject().put("PeeprRoute", JSONObject().put("initialTimeline",
            JSONObject().put("objects", JSONArray().put(post))))))
    private fun post() = JSONObject(fixture.substringAfter('>').substringBefore("</script>"))
        .getJSONObject("PeeprRoute").getJSONObject("initialTimeline").getJSONArray("objects").getJSONObject(0)
    private fun JSONObject.removeLayout() = apply { remove("layout") }
    private fun image(name: String) = JSONObject().put("type", "image").put("media",
        JSONArray().put(JSONObject().put("url", "https://cdn.example/$name.png").put("type", "image/png")))
    private fun video() = JSONObject("""{"type":"video","provider":"tumblr",
        "url":"https://va.media.tumblr.com/unused.mp4","embedUrl":"https://sample-blog.tumblr.com/post/172687798174/embed",
        "media":{"url":"https://va.media.tumblr.com/native.mp4","type":"video/mp4","width":1920,"height":1080},
        "poster":[{"url":"https://64.media.tumblr.com/poster.jpg","type":"image/jpeg"}],"duration":7500}""")
    private fun urls(media: List<ExtractedMedia>) = media.map {
        when (it) {
            is ExtractedMedia.Image -> it.source.url
            is ExtractedMedia.Video -> it.videoSource.url
            else -> error("Unexpected audio")
        }
    }
    private fun envelope(state: JSONObject) = "<script id='___INITIAL_STATE___' type='application/json'>$state</script>"
    private val fixture = javaClass.getResource("/tumblr/photo-post.html")!!.readText()

    private companion object {
        const val PAGE = "https://www.tumblr.com/sample-blog/172687798174"
    }
}
