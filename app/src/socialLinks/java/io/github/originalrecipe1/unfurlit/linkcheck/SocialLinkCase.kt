package io.github.originalrecipe1.unfurlit.linkcheck

import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * One entry of `social-links.json`, shared by the live `SocialLinksTest` and the linkCheck
 * build. [expected] is `success` or an `ExtractionError` name; [media], [count], [minCount]
 * and [soundtrack] further constrain a success.
 */
class SocialLinkCase(
    val id: String,
    val url: String,
    val expected: String,
    /** Every item must be one of these kinds, and each kind must appear. */
    val media: Set<String>?,
    val count: Int?,
    val minCount: Int?,
    /** Whether a photo post must (true) or must not (false) have a separate soundtrack. */
    val soundtrack: Boolean?,
) {
    val succeeds: Boolean
        get() = expected == SUCCESS

    /** What a success must contain, such as "3 images, with soundtrack"; null if any media will do. */
    val mediaExpectation: String?
        get() {
            val noun = media?.let { kinds -> MEDIA_KINDS.filter(kinds::contains).joinToString("+") }
            val items = when {
                count != null -> quantity(count, noun ?: "item")
                minCount != null -> "at least " + quantity(minCount, noun ?: "item")
                else -> noun
            }
            val details = listOfNotNull(
                items,
                soundtrack?.let { if (it) "with soundtrack" else "without soundtrack" },
            )
            return details.joinToString(", ").takeIf(String::isNotEmpty)
        }

    val expectation: String
        get() = when {
            !succeeds -> expected
            else -> mediaExpectation?.let { "$SUCCESS: $it" } ?: SUCCESS
        }

    fun problemsWith(observed: SocialLinkObservation): List<String> {
        if (observed.outcome != expected) return listOf("wrong outcome")
        if (expected != SUCCESS) return emptyList()
        val found = observed.kinds.toSet()
        return buildList<String> {
            if (found.isEmpty()) add("no media")
            media?.let { wanted ->
                (found - wanted).takeIf { it.isNotEmpty() }?.let { add("unexpected ${it.joinToString()}") }
                (wanted - found).takeIf { it.isNotEmpty() }?.let { add("missing ${it.joinToString()}") }
            }
            val size = observed.kinds.size
            if (count != null && size != count) add("${quantity(size, "item")} instead of $count")
            if (minCount != null && size < minCount) add("${quantity(size, "item")} instead of at least $minCount")
            if (soundtrack != null && observed.soundtrack != soundtrack) {
                add(if (soundtrack) "no soundtrack" else "unexpected soundtrack")
            }
        }
    }

    override fun toString() = id

    companion object {
        fun parseAll(json: String): List<SocialLinkCase> {
            val cases = JSONArray(json)
            return (0 until cases.length()).map { index -> fromJson(cases.getJSONObject(index)) }
        }

        fun fromJson(json: JSONObject): SocialLinkCase {
            val id = json.getString("id")
            val media = when (val value = json.opt("media")) {
                null -> null
                is String -> setOf(value)
                is JSONArray -> (0 until value.length()).map(value::getString).toSet()
                else -> throw IllegalArgumentException("$id: media must be a kind or a list of kinds")
            }
            require(media == null || media.isNotEmpty() && MEDIA_KINDS.containsAll(media)) {
                "$id: media kinds must be among $MEDIA_KINDS"
            }
            val case = SocialLinkCase(
                id = id,
                url = json.getString("url"),
                expected = json.getString("expected"),
                media = media,
                count = if (json.has("count")) json.getInt("count") else null,
                minCount = if (json.has("minCount")) json.getInt("minCount") else null,
                soundtrack = if (json.has("soundtrack")) json.getBoolean("soundtrack") else null,
            )
            require(
                case.expected == SUCCESS ||
                    media == null && case.count == null && case.minCount == null && case.soundtrack == null,
            ) { "$id: only successful cases can describe media" }
            return case
        }
    }
}

/** What an extraction produced, without URLs, headers or cookies. */
data class SocialLinkObservation(
    val outcome: String,
    /** The kind of each extracted item, in order. */
    val kinds: List<String> = emptyList(),
    /** Stream formats of the video and audio items. */
    val formats: Set<String> = emptySet(),
    /** Whether a video plays its audio from a separate stream. */
    val separateAudio: Boolean = false,
    val soundtrack: Boolean = false,
    val platform: String? = null,
) {
    override fun toString(): String {
        if (outcome != SUCCESS) return outcome
        val items = if (kinds.isEmpty()) {
            "no media"
        } else {
            MEDIA_KINDS.filter(kinds::contains)
                .joinToString { kind -> quantity(kinds.count { it == kind }, kind) }
        }
        val streams = listOfNotNull(
            formats.takeIf { it.isNotEmpty() }?.joinToString("/"),
            "separate audio".takeIf { separateAudio },
        )
        return buildString {
            append(SUCCESS).append(": ").append(items)
            if (streams.isNotEmpty()) append(" (").append(streams.joinToString(", ")).append(')')
            if (soundtrack) append(", soundtrack")
            platform?.let { append(", from ").append(it) }
        }
    }

    companion object {
        fun of(result: ExtractionResult) = SocialLinkObservation(
            outcome = SUCCESS,
            kinds = result.media.map { media ->
                when (media) {
                    is ExtractedMedia.Video -> "video"
                    is ExtractedMedia.Image -> "image"
                    is ExtractedMedia.Audio -> "audio"
                }
            },
            formats = result.media.flatMap { media ->
                when (media) {
                    is ExtractedMedia.Video -> listOfNotNull(media.videoSource, media.audioSource)
                    is ExtractedMedia.Audio -> listOf(media.source)
                    is ExtractedMedia.Image -> emptyList()
                }
            }.map { it.format.name }.toSortedSet(),
            separateAudio = result.media.any { it is ExtractedMedia.Video && it.audioSource != null },
            soundtrack = result.backgroundAudio != null,
            platform = result.platform,
        )
    }
}

private const val SUCCESS = "success"
private val MEDIA_KINDS = listOf("video", "image", "audio")

private fun quantity(count: Int, noun: String) =
    if (count == 1 || noun == "audio" || '+' in noun) "$count $noun" else "$count ${noun}s"
