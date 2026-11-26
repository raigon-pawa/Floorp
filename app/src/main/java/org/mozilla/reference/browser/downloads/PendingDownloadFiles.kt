/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.downloads

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.feature.downloads.filewriter.DefaultDownloadFileWriter
import mozilla.components.feature.downloads.filewriter.DownloadFileWriter
import mozilla.components.support.utils.DownloadFileUtils

/**
 * Android Components' writer publishes the MediaStore entry under its real name whenever the stream ends, so a paused
 * download showed up in Files as a finished but truncated file. This one keeps the entry pending (hidden, under a
 * temporary name) until all of it is written, and finds it again, pending, to resume. Files outside the public
 * Downloads folder and the pre-scoped-storage path go to the library's writer.
 */
class PendingUntilCompleteFileWriter(
    private val context: Context,
    private val downloadFileUtils: DownloadFileUtils,
    private val isStillDownloading: (DownloadState) -> Boolean,
) : DownloadFileWriter {
    private val fallback = DefaultDownloadFileWriter(context, downloadFileUtils)

    override fun useFileStream(
        download: DownloadState,
        append: Boolean,
        shouldUseScopedStorage: Boolean,
        onUpdateState: (DownloadState) -> Unit,
        block: (OutputStream) -> Unit,
    ) {
        val fileName = download.fileName
        val inPublicDownloads = fileName != null && download.directoryPath == publicDownloads()
        // shortcut: needs Android 11 to find pending entries; Android 10 keeps the library's behaviour.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !shouldUseScopedStorage || !inPublicDownloads) {
            fallback.useFileStream(download, append, shouldUseScopedStorage, onUpdateState, block)
            return
        }
        val named =
            if (append) {
                download
            } else {
                download.copy(
                    fileName = downloadFileUtils.uniqueFileName(download.directoryPath, requireNotNull(fileName))
                )
            }
        onUpdateState(named)
        val name = requireNotNull(named.fileName)
        val resolver = context.contentResolver
        val uri =
            (if (append) resolver.findDownload(name) else null)
                ?: resolver.insert(
                    collection(),
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(
                            MediaStore.MediaColumns.MIME_TYPE,
                            downloadFileUtils.getSafeContentType(name, named.contentType),
                        )
                    },
                )
                ?: throw IOException("Could not create the download $name")

        val size =
            (resolver.openFileDescriptor(uri, if (append) "wa" else "w") ?: throw IOException("Could not open $uri"))
                .use { descriptor ->
                    FileOutputStream(descriptor.fileDescriptor).use(block)
                    descriptor.statSize
                }
        // The stream also ends when the download is paused, cancelled or the connection drops.
        val complete = named.contentLength?.let { size >= it } ?: isStillDownloading(named)
        if (complete) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun collection(): Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

private fun publicDownloads() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path

/** The app's download entry named [fileName] in the public Downloads folder, pending or not. */
@RequiresApi(Build.VERSION_CODES.R)
private fun ContentResolver.findDownload(fileName: String): Uri? {
    val args =
        Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Downloads.DISPLAY_NAME} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(fileName))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
    return query(collection(), arrayOf(MediaStore.Downloads._ID), args, null)?.use { cursor ->
        if (cursor.moveToFirst()) ContentUris.withAppendedId(collection(), cursor.getLong(0)) else null
    }
}

/**
 * Resuming a paused download first checks that its file exists, and the library looks only for published entries, so a
 * download kept pending by [PendingUntilCompleteFileWriter] would fail to resume. Finds pending ones as well, and
 * deletes them: cancelling a download, or restarting one whose server ignored the resume range, deletes its partial
 * file, which would otherwise stay hidden in Downloads until MediaStore expires it (and the restart failed every time).
 */
class PendingAwareDownloadFileUtils(
    private val context: Context,
    private val delegate: DownloadFileUtils,
) : DownloadFileUtils by delegate {
    override fun fileExists(directoryPath: String, fileName: String?): Boolean =
        delegate.fileExists(directoryPath, fileName) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                fileName != null &&
                context.contentResolver.findDownload(fileName) != null)

    // The service's notification and its Open action start this; see OpenDownloadActivity.
    override fun createOpenFileIntent(fileName: String?, directoryPath: String, downloadContentType: String?): Intent? =
        delegate.createOpenFileIntent(fileName, directoryPath, downloadContentType)?.let {
            OpenDownloadActivity.intentFor(context, it, fileName)
        }

    override fun deleteMediaFile(contentResolver: ContentResolver, fileName: String?, directoryPath: String): Boolean =
        delegate.deleteMediaFile(contentResolver, fileName, directoryPath) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                fileName != null &&
                directoryPath == publicDownloads() &&
                contentResolver.findDownload(fileName)?.let { contentResolver.delete(it, null, null) > 0 } == true)
}
