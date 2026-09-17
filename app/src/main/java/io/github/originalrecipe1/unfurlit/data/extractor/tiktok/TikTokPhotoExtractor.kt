package io.github.originalrecipe1.unfurlit.data.extractor.tiktok

import io.github.originalrecipe1.unfurlit.data.network.PublicPageLoader
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import okhttp3.Request

internal class TikTokPhotoExtractor {
    suspend fun extract(sourceUrl: String, pageUrl: String): ExtractionResult =
        TikTokPhotoParser.parse(sourceUrl, pageUrl,
            PublicPageLoader.load(Request.Builder().url(pageUrl).build()))
}
