/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import mozilla.components.browser.state.selector.findCustomTabOrSelectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.concept.engine.prompt.PromptRequest
import mozilla.components.support.ktx.android.content.isPermissionGranted

/**
 * Keeps the document picker of a file input usable when the camera, microphone and media permissions are denied.
 *
 * PromptFeature asks for those permissions before every file picker (they add capture and media options) and cancels
 * the input when all of them are denied, although the document picker itself needs none of them from Android 13 on.
 */
object FilePickerPermissions {
    /** True when the document picker can open without any permission being granted now. */
    fun canOpenDocumentPicker(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || context.isPermissionGranted(READ_EXTERNAL_STORAGE)

    /**
     * True when the pending file input of [tabId] (or the selected tab) has no accept types: it is a plain document
     * input, so camera, microphone and media access are not asked for it.
     */
    fun isPlainFileInput(state: BrowserState, tabId: String?): Boolean {
        val request =
            state.findCustomTabOrSelectedTab(tabId)?.content?.promptRequests?.lastOrNull { it is PromptRequest.File }
        return (request as? PromptRequest.File)?.mimeTypes?.isEmpty() == true
    }

    /**
     * The results to hand to PromptFeature: all granted when the document picker can open anyway, so it opens with the
     * options whose permissions really are granted (it checks each one again), instead of cancelling the input.
     */
    fun grantResults(results: IntArray, documentPickerAllowed: Boolean): IntArray =
        if (documentPickerAllowed) IntArray(results.size) { PERMISSION_GRANTED } else results
}
