/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.downloads

import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.support.utils.DefaultDownloadFileUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** A partial download stays a hidden, pending MediaStore entry; it must still be found and deleted. */
class PendingDownloadFilesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
    private val fileUtils =
        PendingAwareDownloadFileUtils(context, DefaultDownloadFileUtils(context, downloadLocation = { downloads }))

    @Test
    fun aPartialDownloadCanBeDeleted() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val name = "aw-partial-${SystemClock.uptimeMillis()}.bin"
        val download =
            DownloadState(
                url = "https://example.org/$name",
                fileName = name,
                directoryPath = downloads,
                contentLength = 100,
            )
        // Half of it written, as when a download is cancelled or interrupted: the entry stays pending.
        PendingUntilCompleteFileWriter(context, fileUtils) { false }
            .useFileStream(download, append = true, shouldUseScopedStorage = true, onUpdateState = {}) {
                it.write(ByteArray(50))
            }
        assertTrue(fileUtils.fileExists(downloads, name))

        assertTrue(fileUtils.deleteMediaFile(context.contentResolver, name, downloads))
        assertFalse(fileUtils.fileExists(downloads, name))
    }
}
