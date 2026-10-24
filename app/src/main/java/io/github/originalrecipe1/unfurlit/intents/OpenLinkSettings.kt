package io.github.originalrecipe1.unfurlit.intents

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi

/**
 * Android 12+ only opens unverified web links in an app after the user selects its
 * domains under Open by default. Unfurlit only reads that choice; it never changes it.
 */
object OpenLinkSettings {
    /** Below Android 12 the system already offers Unfurlit in its link chooser. */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    val isNeeded: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * Opens this app's Open by default page, or its App info page where a device lacks it.
     * Returns false if neither could be opened.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun open(context: Context, startActivity: (Intent) -> Unit = context::startActivity): Boolean {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        return listOf(
            Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        ).any { action ->
            try {
                startActivity(Intent(action, packageUri))
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
    }

    /** How many of Unfurlit's link domains the user turned on, or null if unknown. */
    @RequiresApi(Build.VERSION_CODES.S)
    fun selectedDomains(context: Context): SelectedDomains? {
        val manager = context.getSystemService(DomainVerificationManager::class.java) ?: return null
        val state = runCatching { manager.getDomainVerificationUserState(context.packageName) }
            .getOrNull() ?: return null
        val domains = state.hostToStateMap
        val enabled = if (state.isLinkHandlingAllowed) {
            domains.values.count { it != DomainVerificationUserState.DOMAIN_STATE_NONE }
        } else {
            0
        }
        return SelectedDomains(enabled = enabled, total = domains.size)
    }
}

data class SelectedDomains(val enabled: Int, val total: Int)
