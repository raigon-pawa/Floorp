/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import kotlin.system.exitProcess
import mozilla.components.support.ktx.android.view.showKeyboard
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.settings.Settings as RBSettings

/** Asks for the add-on collection to use instead of the default one. Survives rotation with what was typed. */
class CustomCollectionDialogFragment : DialogFragment() {
    private lateinit var userView: EditText
    private lateinit var collectionView: EditText

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val dialogView = View.inflate(context, R.layout.amo_collection_override_dialog, null)
        userView = dialogView.findViewById(R.id.custom_amo_user)
        collectionView = dialogView.findViewById(R.id.custom_amo_collection)
        // After a rotation the fields get back what was typed from the dialog's saved state.
        if (savedInstanceState == null) {
            collectionView.setText(RBSettings.getOverrideAmoCollection(context))
            userView.setText(RBSettings.getOverrideAmoUser(context))
            userView.requestFocus()
            userView.showKeyboard()
        }
        userView.doAfterTextChanged { updateOkButton() }
        collectionView.doAfterTextChanged { updateOkButton() }

        return AlertDialog.Builder(context)
            .setTitle(R.string.preferences_customize_amo_collection)
            .setMessage(R.string.archwave_addons_custom_collection_message)
            .setView(dialogView)
            .setNegativeButton(R.string.customize_addon_collection_cancel) { dialog, _ -> dialog.cancel() }
            .setPositiveButton(R.string.customize_addon_collection_ok) { _, _ -> save() }
            .create()
    }

    override fun onStart() {
        super.onStart()
        updateOkButton()
    }

    // One field alone selects nothing: OK takes both, or neither for the default collection.
    private fun updateOkButton() {
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
            userView.text.isBlank() == collectionView.text.isBlank()
    }

    private fun save() {
        val context = requireContext().applicationContext
        // Only both fields together select a collection; restart only if the one in use changes.
        fun collectionInUse() =
            (RBSettings.getOverrideAmoUser(context) to RBSettings.getOverrideAmoCollection(context)).takeIf {
                RBSettings.isAmoCollectionOverrideConfigured(context)
            }
        val before = collectionInUse()
        RBSettings.setOverrideAmoUser(context, userView.text.toString())
        RBSettings.setOverrideAmoCollection(context, collectionView.text.toString())
        if (collectionInUse() == before) return

        Toast.makeText(context, context.getString(R.string.toast_customize_addon_collection_done), Toast.LENGTH_LONG)
            .show()

        // The add-on provider reads the collection once per process, so start the app again in a new one.
        Handler(Looper.getMainLooper())
            .postDelayed(
                {
                    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    launch?.component?.let { context.startActivity(Intent.makeRestartActivityTask(it)) }
                    exitProcess(0)
                },
                EXIT_DELAY,
            )
    }

    companion object {
        private const val TAG = "custom_amo_collection"
        private const val EXIT_DELAY = 3000L

        /** Shows the dialog unless it is already showing. */
        fun show(manager: FragmentManager) {
            if (manager.findFragmentByTag(TAG) != null) return
            CustomCollectionDialogFragment().show(manager, TAG)
        }
    }
}
