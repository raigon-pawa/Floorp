/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.downloads

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import mozilla.components.feature.downloads.R as DownloadsR
import mozilla.components.support.utils.ext.getParcelableExtraCompat

/**
 * Opens a finished download from its notification. The notification used to start the viewer itself, and when no app
 * could open the file the tap did nothing and the notification went away: the only way to the file, with no downloads
 * list in the app. This says so instead, and shows the system's Downloads, where the file is.
 */
class OpenDownloadActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = intent.getParcelableExtraCompat(Intent.EXTRA_INTENT, Intent::class.java)
        try {
            view?.let(::startActivity)
        } catch (_: ActivityNotFoundException) {
            val extension = MimeTypeMap.getFileExtensionFromUrl(intent.getStringExtra(EXTRA_FILE_NAME).orEmpty())
            val message = getString(DownloadsR.string.mozac_feature_downloads_open_not_supported1, extension)
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                // No Downloads screen on this device: the message is all there is.
            }
        }
        finish()
    }

    /** Builds the intents that open a download through this activity. */
    companion object {
        private const val EXTRA_FILE_NAME = "archwave.download_file_name"

        /** An intent that opens [view], the viewer intent of the download named [fileName]. */
        fun intentFor(context: Context, view: Intent, fileName: String?): Intent =
            Intent(context, OpenDownloadActivity::class.java)
                // Tells the notifications' PendingIntents apart: extras do not, and each would open the first file.
                .setData(view.data)
                .putExtra(Intent.EXTRA_INTENT, view)
                .putExtra(EXTRA_FILE_NAME, fileName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
