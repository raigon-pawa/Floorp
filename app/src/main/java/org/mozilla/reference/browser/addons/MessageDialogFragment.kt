/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.addons

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import mozilla.components.ui.widgets.withCenterAlignedButtons

/**
 * A message with an OK button, or a question with Cancel and a confirm button, that survives rotation (a plain
 * AlertDialog vanishes and leaks its window). The confirm button sends an empty fragment result under the request key.
 */
class MessageDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val arguments = requireArguments()
        val requestKey = arguments.getString(KEY_REQUEST)
        val builder =
            AlertDialog.Builder(requireContext())
                .setTitle(arguments.getString(KEY_TITLE))
                .setMessage(arguments.getString(KEY_MESSAGE))
                .setPositiveButton(arguments.getString(KEY_CONFIRM) ?: getString(android.R.string.ok)) { _, _ ->
                    requestKey?.let { parentFragmentManager.setFragmentResult(it, Bundle.EMPTY) }
                }
        if (requestKey != null) builder.setNegativeButton(android.R.string.cancel, null)
        return builder.create().also { it.setOnShowListener { _ -> it.withCenterAlignedButtons() } }
    }

    /** Creates the dialog. */
    companion object {
        private const val KEY_TITLE = "title"
        private const val KEY_MESSAGE = "message"
        private const val KEY_CONFIRM = "confirm"
        private const val KEY_REQUEST = "request"

        /**
         * A dialog with [title] and [message]. Without [requestKey] it has only OK; with it, Cancel and [confirm],
         * which sets a fragment result under [requestKey].
         */
        fun newInstance(
            title: String,
            message: String,
            confirm: String? = null,
            requestKey: String? = null,
        ) =
            MessageDialogFragment().apply {
                arguments =
                    Bundle().apply {
                        putString(KEY_TITLE, title)
                        putString(KEY_MESSAGE, message)
                        putString(KEY_CONFIRM, confirm)
                        putString(KEY_REQUEST, requestKey)
                    }
            }
    }
}
