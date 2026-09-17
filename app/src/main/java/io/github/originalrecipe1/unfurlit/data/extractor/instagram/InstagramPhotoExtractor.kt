package io.github.originalrecipe1.unfurlit.data.extractor.instagram

import io.github.originalrecipe1.unfurlit.data.network.PublicPageLoader
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import okhttp3.Request

internal class InstagramPhotoExtractor {
    suspend fun extract(sourceUrl: String, pageUrl: String): ExtractionResult? {
        val request = Request.Builder().url(pageUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-us,en;q=0.5")
            .header("Sec-Fetch-Mode", "navigate")
            .build()
        return InstagramPhotoParser.parse(sourceUrl, pageUrl, PublicPageLoader.load(request))
    }
}
