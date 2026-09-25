package io.github.originalrecipe1.unfurlit.linkcheck

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/** Seeds History when the link-check app starts; it serves no content. */
class LinkCheckSeedProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        // Providers start before any screen, so History never shows a half-filled list.
        LinkCheckSeeder(context ?: return false).seedIfNeeded()
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
