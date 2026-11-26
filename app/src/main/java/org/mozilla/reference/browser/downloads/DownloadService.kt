/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.downloads

import android.content.Intent
import android.os.Environment
import androidx.core.app.NotificationManagerCompat
import mozilla.components.browser.state.state.content.DownloadState.Status
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.downloads.AbstractFetchDownloadService
import mozilla.components.feature.downloads.DefaultPackageNameProvider
import mozilla.components.feature.downloads.DownloadEstimator
import mozilla.components.feature.downloads.FileSizeFormatter
import mozilla.components.feature.downloads.PackageNameProvider
import mozilla.components.feature.downloads.filewriter.DownloadFileWriter
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.utils.DefaultDownloadFileUtils
import mozilla.components.support.utils.DownloadFileUtils
import org.mozilla.reference.browser.BrowserActivity
import org.mozilla.reference.browser.ext.components

/** Runs downloads in a foreground service so they continue after the user leaves the app. */
class DownloadService : AbstractFetchDownloadService() {
    override val httpClient by lazy { components.core.client }
    override val store: BrowserStore by lazy { components.core.store }
    // Bound to no activity, so it posts but never asks: AskFirstDownloadManager asks before the download starts, and
    // the app's delegate would ask a second time after "Don't allow".
    override val notificationsDelegate: NotificationsDelegate by lazy {
        NotificationsDelegate(NotificationManagerCompat.from(applicationContext))
    }
    override val fileSizeFormatter: FileSizeFormatter by lazy { components.fileSizeFormatter }
    override val downloadEstimator: DownloadEstimator by lazy { components.downloadEstimator }
    override val downloadFileUtils: DownloadFileUtils by lazy {
        PendingAwareDownloadFileUtils(
            applicationContext,
            DefaultDownloadFileUtils(
                context = applicationContext,
                downloadLocation = {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
                },
            ),
        )
    }
    // A paused or interrupted download stays hidden instead of showing up as a finished, truncated file.
    override val downloadFileWriter: DownloadFileWriter by lazy {
        PendingUntilCompleteFileWriter(applicationContext, downloadFileUtils) {
            store.state.downloads[it.id]?.status == Status.DOWNLOADING
        }
    }
    override val packageNameProvider: PackageNameProvider by lazy { DefaultPackageNameProvider(applicationContext) }

    // The base class stops the service when the app is swiped away from recents, which cancelled running downloads
    // and deleted their partial files without a word. A download in progress keeps going in this foreground service.
    // That keeps the process alive, so end private browsing here as the process death would have: the private tabs
    // (and with them their downloads) go. A paused download does not hold the process. Custom tabs and web apps run in
    // tasks of their own: closing one of them leaves the browser and its private tabs alone.
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!BrowserActivity.isBrowserTask(rootIntent)) return
        components.useCases.tabsUseCases.removePrivateTabs()
        if (store.state.downloads.values.none { !it.private && it.status in RUNNING }) super.onTaskRemoved(rootIntent)
    }

    private companion object {
        val RUNNING = setOf(Status.INITIATED, Status.DOWNLOADING)
    }
}
