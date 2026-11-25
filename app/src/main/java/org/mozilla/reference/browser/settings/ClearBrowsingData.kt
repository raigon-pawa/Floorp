/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import mozilla.components.concept.engine.Engine.BrowsingData
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.PERMISSION_TRACKING
import org.mozilla.reference.browser.Components
import org.mozilla.reference.browser.EngineProvider
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.ext.components

/** What the clear-browsing-data dialog offers, in the order it lists them. */
enum class ClearableData(
    @StringRes val label: Int,
    val checkedByDefault: Boolean,
) {
    TABS(R.string.archwave_clear_data_tabs, checkedByDefault = false),
    HISTORY(R.string.archwave_clear_data_history, checkedByDefault = true),
    COOKIES(R.string.archwave_clear_data_cookies, checkedByDefault = true),
    CACHE(R.string.archwave_clear_data_cache, checkedByDefault = true),
    PERMISSIONS(R.string.archwave_clear_data_permissions, checkedByDefault = true),
    DOWNLOADS(R.string.archwave_clear_data_downloads, checkedByDefault = true),
}

/**
 * Clears what the user picked. Everything runs in the application scope, so it finishes when the dialog or the activity
 * goes away. Extensions, logins and the search engine choice are never touched.
 */
object BrowsingDataCleaner {
    private const val PREF_PENDING = "archwave.clear_data_pending"
    private val logger = Logger("BrowsingDataCleaner")

    /** True while a clear started by this process runs; then a pending record is not a leftover from a crash. */
    var running = false
        private set

    /**
     * The engine part of each choice. History lives in the app's own storage, permissions are cleared per site (the
     * engine-wide flag would also reset extensions' permissions), and tabs and downloads are app state.
     */
    fun engineFlags(items: Set<ClearableData>): Int {
        var flags = 0
        if (ClearableData.COOKIES in items) {
            flags = flags or BrowsingData.COOKIES or BrowsingData.DOM_STORAGES or BrowsingData.AUTH_SESSIONS
        }
        if (ClearableData.CACHE in items) flags = flags or BrowsingData.NETWORK_CACHE or BrowsingData.IMAGE_CACHE
        return flags
    }

    /**
     * Choices of a clear that started but never reported back, because the process died. Recorded before clearing
     * starts and removed only when everything succeeded, so a half-done clear is offered again instead of hidden.
     */
    fun pending(context: Context): Set<ClearableData> =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(PREF_PENDING, emptySet())
            .orEmpty()
            .mapNotNull { name -> ClearableData.entries.find { it.name == name } }
            .toSet()

    private fun setPending(
        context: Context,
        items: Set<ClearableData>,
    ) =
        PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true) {
            if (items.isEmpty()) remove(PREF_PENDING) else putStringSet(PREF_PENDING, items.map { it.name }.toSet())
        }

    /** Drops the record of an interrupted clear, when the user declines to repeat it. */
    fun forgetPending(context: Context) = setPending(context, emptySet())

    /** Starts clearing [items] and shows a short message when done. */
    fun start(
        context: Context,
        items: Set<ClearableData>,
    ) {
        val app = context.applicationContext
        val components = app.components
        setPending(app, items)
        running = true
        components.applicationScope.launch {
            val result = runCatching { clear(app, components, items) }
            result.exceptionOrNull()?.let { logger.error("Clearing browsing data failed", it) }
            running = false
            if (result.isSuccess) setPending(app, emptySet())
            val message =
                if (result.isSuccess) R.string.archwave_clear_data_done else R.string.archwave_clear_data_failed
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun clear(
        context: Context,
        components: Components,
        items: Set<ClearableData>,
    ) {
        val tabs = components.useCases.tabsUseCases
        // Closed first, so that no open page writes cookies, storage or history back while the rest is cleared.
        if (ClearableData.TABS in items) tabs.removeAllTabs(recoverable = false)
        try {
            clearData(context, components, items)
        } finally {
            // There is always a tab: a fresh one shows the start page.
            if (components.core.store.state.tabs.isEmpty()) tabs.addTab(START_URL, selectTab = true)
        }
    }

    private suspend fun clearData(
        context: Context,
        components: Components,
        items: Set<ClearableData>,
    ) {
        val engineFlags = engineFlags(items)
        if (engineFlags != 0) {
            suspendCancellableCoroutine { continuation ->
                components.core.engine.clearData(
                    BrowsingData.select(engineFlags),
                    onSuccess = { continuation.resume(Unit) },
                    onError = { continuation.resumeWithException(it) },
                )
            }
        }
        if (ClearableData.HISTORY in items) clearHistory(components)
        if (ClearableData.CACHE in items) {
            withContext(Dispatchers.IO) { components.core.icons.clear() }
            components.core.thumbnailStorage.clearThumbnails().join()
        }
        if (ClearableData.PERMISSIONS in items) clearSitePermissions(context, components)
        if (ClearableData.DOWNLOADS in items) components.useCases.downloadsUseCases.removeAllDownloads()
    }

    /** Places logs and swallows its own errors, so a failed delete would pass as done without the check. */
    private suspend fun clearHistory(components: Components) {
        val history = components.core.historyStorage
        val startedAt = System.currentTimeMillis()
        history.deleteEverything()
        // Visits after the start can come from pages that are still open.
        check(history.getDetailedVisits(0, startedAt).isEmpty()) { "History was not deleted" }
    }

    /** Resets every web site's permissions to "ask", in the app's records and in the engine, but not extensions'. */
    private suspend fun clearSitePermissions(
        context: Context,
        components: Components,
    ) {
        val storage = components.core.geckoSitePermissionsStorage
        storage.all().filter { isWebOrigin(it.origin) }.forEach { storage.remove(it, private = false) }
        // Permissions the app has no record of (granted by the engine itself, or before the record existed).
        val controller = EngineProvider.getOrCreateRuntime(context).storageController
        controller.allPermissions
            .await()
            .filter { isWebOrigin(it.uri) }
            .forEach { permission ->
                // As the site permissions storage removes them: a tracking-protection exception is removed by denying.
                val value =
                    if (permission.permission == PERMISSION_TRACKING) {
                        ContentPermission.VALUE_DENY
                    } else {
                        ContentPermission.VALUE_PROMPT
                    }
                controller.setPermission(permission, value)
            }
    }

    /** Extensions (moz-extension:) and the browser's own pages keep their permissions. */
    fun isWebOrigin(uri: String): Boolean = uri.startsWith("http://") || uri.startsWith("https://")

    private suspend fun GeckoResult<List<ContentPermission>>.await(): List<ContentPermission> =
        suspendCancellableCoroutine { continuation ->
            accept(
                { continuation.resume(it.orEmpty()) },
                { continuation.resumeWithException(it ?: IllegalStateException("No permissions")) },
            )
        }

    private const val START_URL = "about:blank"
}

/** Lets the user pick what to clear. Survives rotation with the boxes as they were. */
class ClearDataDialogFragment : DialogFragment() {
    private lateinit var checked: BooleanArray

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val items = ClearableData.entries
        checked =
            savedInstanceState?.getBooleanArray(KEY_CHECKED)
                ?: requireArguments().getBooleanArray(KEY_CHECKED)
                ?: items.map { it.checkedByDefault }.toBooleanArray()
        val interrupted = requireArguments().getBoolean(KEY_INTERRUPTED)

        return AlertDialog.Builder(requireContext())
            .setTitle(
                if (interrupted) R.string.archwave_clear_data_interrupted else R.string.archwave_settings_clear_data
            )
            .setMultiChoiceItems(items.map { getString(it.label) }.toTypedArray(), checked) { dialog, which, isChecked
                ->
                checked[which] = isChecked
                updateClearButton(dialog as AlertDialog)
            }
            .setNegativeButton(R.string.archwave_clear_data_cancel) { _, _ -> declineIfInterrupted() }
            .setPositiveButton(R.string.archwave_clear_data_clear) { _, _ ->
                BrowsingDataCleaner.start(requireContext(), items.filterIndexed { i, _ -> checked[i] }.toSet())
            }
            .create()
    }

    override fun onStart() {
        super.onStart()
        updateClearButton(dialog as AlertDialog)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBooleanArray(KEY_CHECKED, checked)
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        declineIfInterrupted()
    }

    // Declining to repeat an interrupted clear: do not ask again.
    private fun declineIfInterrupted() {
        if (requireArguments().getBoolean(KEY_INTERRUPTED)) BrowsingDataCleaner.forgetPending(requireContext())
    }

    private fun updateClearButton(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = checked.any { it }
    }

    companion object {
        private const val TAG = "clear_browsing_data"
        private const val KEY_CHECKED = "checked"
        private const val KEY_INTERRUPTED = "interrupted"

        /** Shows the dialog unless it is already showing. */
        fun show(manager: FragmentManager) {
            if (manager.findFragmentByTag(TAG) != null) return
            ClearDataDialogFragment().apply { arguments = Bundle() }.show(manager, TAG)
        }

        /** Offers to finish a clear the process did not live to complete, with the same boxes checked. */
        fun showIfInterrupted(context: Context, manager: FragmentManager) {
            val pending = BrowsingDataCleaner.pending(context)
            if (pending.isEmpty() || BrowsingDataCleaner.running || manager.findFragmentByTag(TAG) != null) return
            ClearDataDialogFragment()
                .apply {
                    arguments =
                        Bundle().apply {
                            putBoolean(KEY_INTERRUPTED, true)
                            putBooleanArray(KEY_CHECKED, ClearableData.entries.map { it in pending }.toBooleanArray())
                        }
                }
                .show(manager, TAG)
        }
    }
}
