package io.github.originalrecipe1.unfurlit.data.extractor.ytdlp

import io.github.originalrecipe1.unfurlit.domain.model.BlockingPlatform
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class ExtractionErrorMapperTest {
    @Test fun classifiesRepresentativeSiteFailures() {
        val cases = mapOf(
            "Unsupported URL: https://example.com" to ExtractionError.UnsupportedUrl,
            "No suitable extractor found for URL https://www.reddit.com/gallery/hrrh23" to ExtractionError.UnsupportedUrl,
            "No video could be found in this post" to ExtractionError.UnsupportedUrl,
            "No video formats found!" to ExtractionError.UnsupportedUrl,
            "[Reddit] 1wop7o6: No media found" to ExtractionError.UnsupportedUrl,
            "HTTP Error 404: Not Found" to ExtractionError.MediaUnavailable,
            "HTTP Error 410: Gone" to ExtractionError.MediaUnavailable,
            "Video has been removed" to ExtractionError.MediaUnavailable,
            "Private video. Sign in" to ExtractionError.AuthenticationRequired,
            "Login required" to ExtractionError.AuthenticationRequired,
            "The web client only works when logged-in" to ExtractionError.AuthenticationRequired,
            "Blocked due to its TLS fingerprint. May compromise security/cookies" to ExtractionError.ExtractionFailed,
            "Connection timed out" to ExtractionError.NetworkFailure,
            "Unexpected extractor response" to ExtractionError.ExtractionFailed,
        )
        cases.forEach { (message, expected) ->
            assertEquals(message, expected, RuntimeException(message).toDomainError())
        }
    }

    @Test fun classifiesResolverFailuresAsNetworkFailures() {
        listOf(
            // Exact message from live run 38414484055, instagram-video-carousel.
            "ERROR: [Instagram] BtOvDOfhvRr: Unable to download webpage: [Errno 7] No address associated with " +
                "hostname (caused by TransportError('[Errno 7] No address associated with hostname'))",
            "ERROR: [vimeo] 76979871: Unable to download webpage: [Errno -3] Temporary failure in name resolution",
            "ERROR: [generic] video: Unable to download webpage: [Errno -2] Name or service not known",
        ).forEach { message ->
            assertEquals(message, ExtractionError.NetworkFailure, RuntimeException(message).toDomainError())
        }
    }

    @Test fun keepsSimilarNonResolverMessagesOutOfNetworkFailures() {
        listOf(
            "ERROR: [Instagram] abc: No address associated with this account",
            "ERROR: [Instagram] abc: Temporary failure, please retry",
        ).forEach { message ->
            assertEquals(message, ExtractionError.ExtractionFailed, RuntimeException(message).toDomainError())
        }
    }

    @Test fun classifiesPlatformNetworkBlocks() {
        // Messages from live run 38414484055, plus the ASCII-apostrophe variant.
        val cases = mapOf(
            "ERROR: [youtube] eRsGyueVLvQ: Sign in to confirm you’re not a bot. Use --cookies-from-browser or " +
                "--cookies for the authentication." to BlockingPlatform.YouTube,
            "ERROR: [youtube] eRsGyueVLvQ: Sign in to confirm you're not a bot." to BlockingPlatform.YouTube,
            "ERROR: [Reddit] 6rrwyj: Account authentication is required. Use --cookies-from-browser or " +
                "--cookies for the authentication." to BlockingPlatform.Reddit,
            "gallery-dl AbortExtraction 0: \"You've been blocked by network security.\"" to BlockingPlatform.Reddit,
            "gallery-dl HttpError 403: '403 Blocked' for 'https://www.reddit.com/r/pics/comments/abc/'" to BlockingPlatform.Reddit,
        )
        cases.forEach { (message, platform) ->
            assertEquals(message, ExtractionError.NetworkBlocked(platform), RuntimeException(message).toDomainError())
        }
    }

    @Test fun mapsRedditBlocksFromEitherEngineToReddit() {
        mapOf(
            "yt-dlp" to "ERROR: [Reddit] 6rrwyj: Account authentication is required.",
            // GalleryDlJsonParser's detail text for the same block.
            "gallery-dl" to "gallery-dl AbortExtraction 0: \"You've been blocked by network security.\"",
        ).forEach { (engine, message) ->
            val error = RuntimeException(message).toDomainError()
            assertEquals(engine, BlockingPlatform.Reddit, (error as? ExtractionError.NetworkBlocked)?.platform)
        }
    }

    @Test fun findsBlocksBehindWrappersAndIoCauses() {
        val block = IOException("ERROR: [youtube] abc: Sign in to confirm you're not a bot.")
        assertEquals(ExtractionError.NetworkBlocked(BlockingPlatform.YouTube),
            RuntimeException("Extraction failed", block).toDomainError())
    }

    @Test fun keepsGenuineSignInFailuresAsAuthentication() {
        listOf(
            "ERROR: [youtube] x2zTi8aEjsA: Please sign in. Use --cookies-from-browser or --cookies for the authentication.",
            "ERROR: [youtube] abc: Sign in to confirm your age. This video may be inappropriate for some users.",
            "ERROR: [youtube] abc: Private video. Sign in if you've been granted access to this video",
            "ERROR: [Reddit] 6rrwyj: This post is NSFW. Login required",
            // The phrase from another extractor, or without the post ID, is not Reddit's block.
            "ERROR: [Tumblr] 172687798174: Account authentication is required.",
            "ERROR: [Reddit]: Account authentication is required.",
            "ERROR: [vimeo] 392479337: The web client only works when logged-in.",
            "Sign in to confirm you're not a botany expert",
            "ERROR: [Reddit] 6rrwyj: Account authentication is requiredOther",
        ).forEach { message ->
            assertEquals(message, ExtractionError.AuthenticationRequired, RuntimeException(message).toDomainError())
        }
    }

    @Test fun recognizesWrappedNetworkFailure() {
        assertEquals(ExtractionError.NetworkFailure,
            RuntimeException("Extraction failed", IOException()).toDomainError())
    }
}
