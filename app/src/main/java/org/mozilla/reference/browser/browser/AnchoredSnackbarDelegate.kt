/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.view.View
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.view.isVisible
import com.google.android.material.snackbar.Snackbar
import mozilla.components.ui.widgets.SnackbarDelegate

/**
 * Shows the library's snackbars (for example "New tab opened") above [anchor], the bottom toolbar, instead of over its
 * top half. A hidden toolbar (full screen) leaves them at the bottom of the screen.
 */
class AnchoredSnackbarDelegate(private val anchor: View) : SnackbarDelegate {
    override fun show(
        snackBarParentView: View,
        text: String,
        subText: String?,
        subTextOverflow: TextOverflow?,
        duration: Int,
        isError: Boolean,
        action: String?,
        withDismissAction: Boolean,
        listener: ((v: View) -> Unit)?,
    ) {
        val snackbar = Snackbar.make(snackBarParentView, text, duration).setAnchorView(anchor.takeIf { it.isVisible })
        if (action != null && listener != null) {
            snackbar.setAction(action) { view -> listener.invoke(view) }
        }
        snackbar.show()
    }

    override fun show(
        snackBarParentView: View,
        text: Int,
        subText: String?,
        subTextOverflow: TextOverflow?,
        duration: Int,
        isError: Boolean,
        action: Int,
        withDismissAction: Boolean,
        listener: ((v: View) -> Unit)?,
    ) =
        show(
            snackBarParentView = snackBarParentView,
            text = snackBarParentView.context.getString(text),
            subText = subText,
            subTextOverflow = subTextOverflow,
            duration = duration,
            isError = isError,
            action = if (action == 0) null else snackBarParentView.context.getString(action),
            withDismissAction = withDismissAction,
            listener = listener,
        )
}
