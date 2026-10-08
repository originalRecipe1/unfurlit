package io.github.originalrecipe1.unfurlit.data.extractor.tumblr

import io.github.originalrecipe1.unfurlit.data.network.PublicPageLoader
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import okhttp3.Request

internal class TumblrPhotoExtractor(
    private val loadPage: suspend (Request) -> String = PublicPageLoader::load,
) {
    suspend fun extract(sourceUrl: String, pageUrl: String): ExtractionResult? {
        val request = Request.Builder().url(pageUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        return TumblrPhotoParser.parse(sourceUrl, pageUrl, loadPage(request))
    }
}
