/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.createTab
import mozilla.components.concept.engine.prompt.PromptRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a file input skips the camera, microphone and media permission prompts, and when a denial still opens it. */
class FilePickerPermissionsTest {
    private fun fileRequest(vararg mimeTypes: String) =
        PromptRequest.File(
            arrayOf(*mimeTypes),
            onSingleFileSelected = { _, _ -> },
            onMultipleFilesSelected = { _, _ -> },
            onDismiss = {},
        )

    private fun stateWith(request: PromptRequest?): BrowserState {
        val tab =
            createTab("https://example.com", id = "t").let {
                if (request == null) it else it.copy(content = it.content.copy(promptRequests = listOf(request)))
            }
        return BrowserState(tabs = listOf(tab), selectedTabId = "t")
    }

    @Test
    fun `only an input without accept types is plain`() {
        assertTrue(FilePickerPermissions.isPlainFileInput(stateWith(fileRequest()), null))
        assertFalse(FilePickerPermissions.isPlainFileInput(stateWith(fileRequest("image/*")), null))
        assertFalse(FilePickerPermissions.isPlainFileInput(stateWith(null), null))
    }

    @Test
    fun `denials still open the picker when the document picker needs no permission`() {
        val denied = intArrayOf(PERMISSION_DENIED, PERMISSION_DENIED)
        assertArrayEquals(
            intArrayOf(PERMISSION_GRANTED, PERMISSION_GRANTED),
            FilePickerPermissions.grantResults(denied, true),
        )
        assertArrayEquals(denied, FilePickerPermissions.grantResults(denied, false))
    }
}
