package io.github.originalrecipe1.unfurlit.data.extractor.pixiv

import io.github.originalrecipe1.unfurlit.data.network.PublicPageLoader
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PixivArtworkParserTest {
    @Test fun recognizesOnlyArtworkUrlsOnPixiv() {
        for (url in listOf(
            PAGE, "$PAGE/", "https://pixiv.net/en/artworks/966412?lang=en#comment",
            "https://touch.pixiv.net/artworks/966412", "https://www.pixiv.net/i/966412",
            "https://WWW.PIXIV.NET.:443/artworks/966412",
            "https://www.pixiv.net/member_illust.php?mode=medium&illust_id=966412",
        )) assertEquals(url, PAGE, PixivArtworkParser.canonicalPage(url))
        for (url in listOf(
            "https://www.pixiv.net/users/966412", "https://www.pixiv.net/en/tags/artworks",
            "$PAGE/extra", "https://www.pixiv.net/artworks/unlisted/abc",
            "https://www.pixiv.net/artworks/0", "https://www.pixiv.net/artworks/not-a-number",
            "https://www.pixiv.net/artworks/%39%36%36%34%31%32",
            "https://www.pixiv.net/member_illust.php?illust_id=966412&illust_id=123",
            "https://www.pixiv.net/member_illust.php?illust_id=123%2F456",
            "https://www.pixiv.net.evil.example/artworks/966412",
            "https://evil.example/artworks/966412", "https://www.pixiv.net@evil.example/artworks/966412",
            "https://name:secret@www.pixiv.net/artworks/966412",
            "https://www.pixiv.net:8443/artworks/966412", "http://www.pixiv.net/artworks/966412",
            "file:///artworks/966412", imageUrl(0),
        )) assertNull(url, PixivArtworkParser.canonicalPage(url))
    }

    @Test fun usesTheOriginalImageAndKeepsTheOriginalHistoryUrl() {
        val original = "https://www.pixiv.net/member_illust.php?mode=medium&illust_id=966412"
        val result = PixivArtworkParser.parse(original, artwork())
        assertEquals(original, result.sourceUrl)
        assertEquals("Pixiv", result.platform)
        assertEquals("Sample artwork", result.title)
        assertEquals("Sample artist", result.author)
        assertEquals(listOf(imageUrl(0)), urls(result.media))
        assertEquals(imageUrl(0), result.thumbnailUrl)
        assertEquals(mapOf("Referer" to "https://www.pixiv.net/"), (result.media.single() as ExtractedMedia.Image).source.headers)
        assertNull(result.backgroundAudio)
    }

    @Test fun usesEveryPageInOrderWithoutGuessingHashesOrExtensions() {
        val pages = listOf(imageUrl(0), imageUrl(1).replace(".png", ".jpg"),
            imageUrl(2).replace("966412_p2.png", "966412-abcdef_p2.gif"))
        val result = PixivArtworkParser.parse(PAGE, artwork(body().put("illustType", 1).put("pageCount", 3)), pages(pages))
        assertEquals(pages, urls(result.media))
    }

    @Test fun rejectsAnotherArtworkInsteadOfShowingRecommendations() {
        assertError(ExtractionError.ExtractionFailed) { artwork(body().put("id", "123")) }
    }

    @Test fun doesNotReturnTheStillPreviewOfAnUgoiraAnimation() {
        assertError(ExtractionError.UnsupportedUrl) { artwork(body().put("illustType", 2)) }
        assertError(ExtractionError.UnsupportedUrl) { artwork(body().put("illustType", 99)) }
    }

    @Test fun restrictedArtworkRemainsAuthenticationRequired() {
        for (field in listOf("restrict", "xRestrict")) {
            assertError(ExtractionError.AuthenticationRequired) { artwork(body().put(field, 1)) }
            val missing = body().apply { remove(field) }
            assertError(ExtractionError.ExtractionFailed) { artwork(missing) }
        }
    }

    @Test fun rejectsApiErrorsMissingDataAndMalformedJsonWithoutInventingALoginRequirement() {
        for (json in listOf("not JSON", "<html>challenge</html>", "{}", "{\"body\":{}}",
            "{\"error\":true,\"message\":\"Request failed\",\"body\":[]}", "{\"error\":false,\"body\":[]}")) {
            assertError(ExtractionError.ExtractionFailed) { PixivArtworkParser.artwork("966412", json) }
        }
    }

    @Test fun rejectsEmptyMissingAndOversizedGalleriesWithoutPartialSuccess() {
        for (count in listOf(0, -1, 51)) {
            assertError(ExtractionError.ExtractionFailed) { artwork(body().put("pageCount", count)) }
        }
        val gallery = artwork(body().put("pageCount", 2))
        for (pages in listOf(null, pages(emptyList()), pages(listOf(imageUrl(0))),
            pages(listOf(imageUrl(0), imageUrl(1), imageUrl(2))),
            "{\"error\":false,\"body\":[{\"urls\":{}},{}]}")) {
            assertError(ExtractionError.ExtractionFailed) { PixivArtworkParser.parse(PAGE, gallery, pages) }
        }
        assertEquals(50, PixivArtworkParser.parse(PAGE, artwork(body().put("pageCount", 50)),
            pages((0..49).map(::imageUrl))).media.size)
    }

    @Test fun rejectsOversizedResponses() {
        val oversized = " ".repeat(PublicPageLoader.MAX_PAGE_BYTES + 1)
        assertError(ExtractionError.ExtractionFailed) { PixivArtworkParser.artwork("966412", oversized) }
        assertError(ExtractionError.ExtractionFailed) {
            PixivArtworkParser.parse(PAGE, artwork(body().put("pageCount", 2)), oversized)
        }
    }

    @Test fun rejectsUnsafeUrlsPlaceholdersAndWrongPagesInsteadOfSkippingThem() {
        for (url in listOf(
            "https://127.0.0.1/img-original/966412_p1.png", "https://192.168.1.2/img-original/966412_p1.png",
            "http://i.pximg.net/img-original/966412_p1.png", "file:///966412_p1.png",
            "https://name:secret@i.pximg.net/img-original/966412_p1.png",
            "https://i.pximg.net:8443/img-original/966412_p1.png",
            "https://i.pximg.net.evil.example/img-original/966412_p1.png",
            "https://s.pximg.net/common/images/limit_unknown_360.png",
            imageUrl(1).replace("img-original", "img-master"), imageUrl(1).replace("966412", "123"),
            imageUrl(0),
        )) {
            assertError(ExtractionError.ExtractionFailed) {
                PixivArtworkParser.parse(PAGE, artwork(body().put("pageCount", 2)), pages(listOf(imageUrl(0), url)))
            }
        }
    }

    @Test fun boundsMetadataAndRejectsMissingOriginalRatherThanUsingAThumbnail() {
        val result = PixivArtworkParser.parse(PAGE, artwork(body().put("title", "t".repeat(600)).put("userName", "a".repeat(600))))
        assertEquals(512, result.title!!.length)
        assertEquals(512, result.author!!.length)
        for (missing in listOf(JSONObject.NULL, "", "null")) {
            val body = body().put("urls", JSONObject().put("original", missing).put("regular", imageUrl(0)))
            assertError(ExtractionError.ExtractionFailed) { artwork(body) }
        }
    }

    private fun artwork(body: JSONObject = body()) = PixivArtworkParser.artwork("966412", response(body))
    private fun urls(media: List<ExtractedMedia>) = media.map { (it as ExtractedMedia.Image).source.url }
    private fun assertError(expected: ExtractionError, action: () -> Unit) =
        assertEquals(expected, assertThrows(ExtractionException::class.java, action).error)

    internal companion object {
        const val PAGE = "https://www.pixiv.net/artworks/966412"
        fun imageUrl(index: Int) = "https://i.pximg.net/img-original/img/2008/06/13/00/29/13/966412_p$index.png"
        fun body() = JSONObject("""{"id":"966412","title":"Sample artwork","userName":"Sample artist",
          "illustType":0,"restrict":0,"xRestrict":0,"pageCount":1,"urls":{"original":"${imageUrl(0)}"}}""")
        fun response(body: Any) = JSONObject().put("error", false).put("body", body).toString()
        fun pages(urls: List<String>) = response(JSONArray(urls.map { JSONObject().put("urls", JSONObject().put("original", it)) }))
    }
}
