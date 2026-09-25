package io.github.originalrecipe1.unfurlit.linkcheck

import android.content.Context
import io.github.originalrecipe1.unfurlit.R
import io.github.originalrecipe1.unfurlit.data.history.HistoryDatabase
import io.github.originalrecipe1.unfurlit.domain.model.HistoryEntry
import io.github.originalrecipe1.unfurlit.domain.model.HistoryMediaKind

/**
 * Adds every live test link from `social-links.json` to History, labelled with what it should
 * show, so each one can be opened and checked by hand. A link that opens successfully is
 * recorded as a normal visit at the top, replacing its label; unchecked and failing links keep
 * theirs below.
 */
internal class LinkCheckSeeder(private val context: Context) {
    /**
     * Seeds a fresh install or a cleared History, and adds links that are new in the fixture.
     * Links already in History are left alone, so checked links keep their results.
     */
    fun seedIfNeeded() {
        val fixture = context.assets.open(FIXTURE).bufferedReader().use { it.readText() }
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val version = fixture.hashCode()
        val database = HistoryDatabase(context)
        try {
            val existing = database.readPage(HISTORY_LIMIT).mapTo(HashSet()) { it.sourceUrl }
            if (existing.isNotEmpty() && preferences.getInt(KEY_FIXTURE_VERSION, 0) == version) return
            val cases = SocialLinkCase.parseAll(fixture).filterNot { it.url in existing }
            val newest = System.currentTimeMillis()
            val writable = database.writableDatabase
            writable.beginTransaction()
            try {
                // Newest first, a millisecond apart, so History lists the links in fixture order.
                cases.forEachIndexed { index, case -> database.record(entryFor(case, newest - index)) }
                writable.setTransactionSuccessful()
            } finally {
                writable.endTransaction()
            }
        } finally {
            database.close()
        }
        preferences.edit().putInt(KEY_FIXTURE_VERSION, version).apply()
    }

    private fun entryFor(case: SocialLinkCase, viewedAt: Long): HistoryEntry {
        val kinds = case.media.orEmpty()
        val count = case.count ?: case.minCount ?: if (case.succeeds) 1 else 0
        return HistoryEntry(
            id = 0,
            sourceUrl = case.url,
            platform = "Link check · " + when {
                !case.succeeds -> "Error handling"
                kinds.size > 1 -> "Mixed media"
                "video" in kinds -> "Video"
                "image" in kinds -> "Photos"
                "audio" in kinds -> "Audio"
                else -> "Any media"
            },
            title = case.id,
            author = "Expect " + if (case.succeeds) {
                case.mediaExpectation ?: "any media"
            } else {
                // The failure screen's title, so the result can be compared at a glance.
                ERROR_TITLES[case.expected]?.let { "“${context.getString(it)}”" } ?: case.expected
            },
            mediaKind = when {
                !case.succeeds || kinds.size != 1 -> HistoryMediaKind.Mixed
                "video" in kinds -> HistoryMediaKind.Video
                "audio" in kinds -> HistoryMediaKind.Audio
                count > 1 -> HistoryMediaKind.Gallery
                else -> HistoryMediaKind.Image
            },
            mediaCount = count,
            durationSeconds = null,
            viewedAtEpochMillis = viewedAt,
        )
    }

    private companion object {
        const val FIXTURE = "social-links.json"
        const val PREFERENCES = "link_check"
        const val KEY_FIXTURE_VERSION = "fixture_version"
        const val HISTORY_LIMIT = 1_000
        val ERROR_TITLES = mapOf(
            "UnsupportedUrl" to R.string.error_unsupported_url,
            "MediaUnavailable" to R.string.error_media_unavailable,
            "AuthenticationRequired" to R.string.error_authentication_required,
            "NetworkFailure" to R.string.error_network_failure,
            "Timeout" to R.string.error_timeout,
            "ExtractionFailed" to R.string.error_extraction_failed,
        )
    }
}
