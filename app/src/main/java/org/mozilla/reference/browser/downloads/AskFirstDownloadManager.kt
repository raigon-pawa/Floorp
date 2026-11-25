/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.downloads

import android.Manifest.permission.POST_NOTIFICATIONS
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.TIRAMISU
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.feature.downloads.manager.DownloadManager
import mozilla.components.support.base.android.NotificationsDelegate

/**
 * Asks for the notification permission when the first download starts, and only starts it once that is answered either
 * way. Started at once, as [mozilla.components.feature.downloads.manager.FetchDownloadManager] does, a small download
 * finishes behind the system prompt: its notifications are lost and, after "Allow", a stale progress notification stays
 * behind.
 *
 * @param downloadManager does the download. Give it a [NotificationsDelegate] that is bound to no activity, so it does
 *   not ask a second time after a denial.
 * @param notificationsDelegate the app's delegate, which shows the prompt over the visible activity.
 * @param shouldShowRationale Android's shouldShowRequestPermissionRationale. It is true once the user has said no, and
 *   then a download does not ask again; after a second "no" Android itself stops showing the prompt.
 */
class AskFirstDownloadManager(
    private val downloadManager: DownloadManager,
    private val notificationsDelegate: NotificationsDelegate,
    private val shouldShowRationale: (String) -> Boolean,
) : DownloadManager by downloadManager {
    private val waiting = mutableListOf<() -> Unit>()

    override fun download(download: DownloadState, cookie: String): String? {
        if (
            SDK_INT < TIRAMISU ||
                notificationsDelegate.hasPostNotificationsPermission() ||
                shouldShowRationale(POST_NOTIFICATIONS)
        ) {
            return downloadManager.download(download, cookie)
        }
        waiting += { downloadManager.download(download, cookie) }
        // The delegate keeps only the last callbacks, so they start every download that waits, not just this one.
        notificationsDelegate.requestNotificationPermission(::startWaiting, ::startWaiting)
        // No prompt came up (asked twice this session already, or no activity on screen): nothing to wait for.
        if (!notificationsDelegate.isRequestingPermission) startWaiting()
        // shortcut: a URL scheme the download manager refuses fails silently here instead of with its toast; Gecko
        // only hands over downloads of schemes it supports, so this has not been seen.
        return download.id
    }

    private fun startWaiting() {
        val starts = waiting.toList()
        waiting.clear()
        starts.forEach { it() }
    }
}
