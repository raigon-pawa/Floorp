/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.downloads

import android.Manifest.permission.POST_NOTIFICATIONS
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.S
import android.os.Build.VERSION_CODES.TIRAMISU
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.feature.downloads.manager.DownloadManager
import mozilla.components.support.base.android.DefaultProcessInfoProvider
import mozilla.components.support.base.android.NotificationsDelegate

/**
 * Starts downloads only when their notifications can show and their service can start.
 *
 * On Android 13 and later it asks for the notification permission when a download starts, and starts the download once
 * that is answered either way. Started at once, as [mozilla.components.feature.downloads.manager.FetchDownloadManager]
 * does, a small download finishes behind the system prompt: its notifications are lost and, after "Allow", a stale
 * progress notification stays behind.
 *
 * On Android 12 and later the download middleware silently skips starting the download service while the app is not in
 * the foreground, and the download then stays pending for good. Such a download starts when the app is back.
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
    private val processInfo = DefaultProcessInfoProvider()
    private var waitingForForeground = false

    override fun download(download: DownloadState, cookie: String): String? {
        val askFirst =
            SDK_INT >= TIRAMISU &&
                !notificationsDelegate.hasPostNotificationsPermission() &&
                !shouldShowRationale(POST_NOTIFICATIONS)
        if (!askFirst && mayStartService()) return downloadManager.download(download, cookie)

        waiting += { downloadManager.download(download, cookie) }
        if (askFirst) {
            // The delegate keeps only the last callbacks, so they start every download that waits, not just this one.
            notificationsDelegate.requestNotificationPermission(::startWaiting, ::startWaiting)
        }
        // Nothing to wait for unless a prompt came up (none after two this session, or with no activity on screen).
        if (!askFirst || !notificationsDelegate.isRequestingPermission) {
            startWaiting()
        } else {
            // Another request (a page's notification, another tab's download) can replace the callbacks above before
            // the answer, so the app's return from the prompt starts the downloads too.
            waitUntilForeground()
        }
        // shortcut: a URL scheme the download manager refuses fails silently here instead of with its toast; Gecko
        // only hands over downloads of schemes it supports, so this has not been seen.
        return download.id
    }

    // The same test as the middleware's StartForegroundService, which skips the start when it fails.
    private fun mayStartService() = SDK_INT < S || processInfo.isForegroundImportance()

    private fun startWaiting() {
        if (!mayStartService()) {
            waitUntilForeground()
            return
        }
        val starts = waiting.toList()
        waiting.clear()
        starts.forEach { it() }
    }

    private fun waitUntilForeground() {
        if (waitingForForeground) return
        waitingForForeground = true
        ProcessLifecycleOwner.get()
            .lifecycle
            .addObserver(
                object : DefaultLifecycleObserver {
                    // Also called at once while the app still counts as resumed: shortly after it was left, or with
                    // the prompt still up.
                    override fun onResume(owner: LifecycleOwner) {
                        if (!mayStartService() || notificationsDelegate.isRequestingPermission) return
                        owner.lifecycle.removeObserver(this)
                        waitingForForeground = false
                        startWaiting()
                    }
                }
            )
    }
}
