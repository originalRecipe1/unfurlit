package io.github.originalrecipe1.unfurlit

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.originalrecipe1.unfurlit.data.network.SafeHttpClient
import io.github.originalrecipe1.unfurlit.data.history.HistoryThumbnailFetcher
import io.github.originalrecipe1.unfurlit.data.repository.RepositoryFactory

class UnfurlitApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(HistoryThumbnailFetcher.Factory(RepositoryFactory.historyRepository(context)))
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = SafeHttpClient.streaming,
                    ),
                )
            }
            .build()
}
