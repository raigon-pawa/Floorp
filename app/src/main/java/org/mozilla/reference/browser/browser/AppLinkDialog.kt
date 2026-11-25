/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import mozilla.components.feature.app.links.RedirectDialogData
import mozilla.components.feature.app.links.RedirectDialogFragment
import mozilla.components.feature.app.links.SimpleRedirectDialogFragment

// The library's own limit (MAX_SUCCESSIVE_DIALOG_MILLIS_LIMIT, internal) against pages that chain these dialogs.
private const val MAX_SUCCESSIVE_DIALOG_MILLIS = 500

/**
 * The "Open in <app>?" dialog of AppLinksFeature, which the library makes impossible to close with Back. Here Back (or
 * a tap outside) closes it and counts as Cancel.
 */
fun cancelableAppLinkDialog(data: RedirectDialogData): RedirectDialogFragment =
    SimpleRedirectDialogFragment.newInstance(
            dialogTitleString = data.title,
            dialogMessageString = data.message,
            cancelable = true,
            showCheckbox = data.showCheckbox,
            maxSuccessiveDialogMillisLimit = MAX_SUCCESSIVE_DIALOG_MILLIS,
        )
        .apply {
            // newInstance also makes the fragment itself not cancelable, which wins over the dialog's setting.
            isCancelable = true
            lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START) dialog?.setOnCancelListener { onCancelRedirect() }
                }
            )
        }
