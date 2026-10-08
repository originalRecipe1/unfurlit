package io.github.originalrecipe1.unfurlit.data.extractor.pixiv

import io.github.originalrecipe1.unfurlit.data.network.PublicPageLoader
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import okhttp3.Request

internal class PixivArtworkExtractor(
    private val loadPage: suspend (Request) -> String = PublicPageLoader::load,
) {
    suspend fun extract(sourceUrl: String, pageUrl: String): ExtractionResult {
        val id = PixivArtworkParser.artworkId(pageUrl)
            ?: throw ExtractionException(ExtractionError.UnsupportedUrl)
        suspend fun load(suffix: String): String = loadPage(Request.Builder()
            .url("https://www.pixiv.net/ajax/illust/$id$suffix")
            .header("Referer", "https://www.pixiv.net/")
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json")
            .build())
        val artwork = PixivArtworkParser.artwork(id, load(""))
        return PixivArtworkParser.parse(sourceUrl, artwork, if (artwork.count > 1) load("/pages") else null)
    }
}
