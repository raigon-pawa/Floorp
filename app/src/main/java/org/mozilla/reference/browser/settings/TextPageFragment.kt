/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.mozilla.reference.browser.R

/** A scrolling page of plain text from a string resource or a bundled asset, such as a licence. */
class TextPageFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val args = requireArguments()
        val text =
            args.getString(ARG_ASSET)?.let { asset ->
                context.assets.open(asset).bufferedReader().use { it.readText() }
            } ?: getString(args.getInt(ARG_TEXT))
        val padding = resources.getDimensionPixelSize(R.dimen.archwave_text_page_padding)
        return ScrollView(context).apply {
            id = R.id.archwave_text_page
            addView(
                TextView(context).apply {
                    this.text = text
                    setPadding(padding, padding, padding, padding)
                    setTextColor(ContextCompat.getColor(context, R.color.aw_text_2))
                    setTextIsSelectable(true)
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Read from the arguments, so the title also comes back after rotation and process death.
        (activity as SettingsFragment.ActionBarUpdater).updateTitle(requireArguments().getInt(ARG_TITLE))
    }

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_TEXT = "text"
        private const val ARG_ASSET = "asset"

        /** A page showing a string resource. */
        fun fromText(
            @StringRes title: Int,
            @StringRes text: Int,
        ) = page(title) { putInt(ARG_TEXT, text) }

        /** A page showing a text file from the app's assets. */
        fun fromAsset(
            @StringRes title: Int,
            asset: String,
        ) = page(title) { putString(ARG_ASSET, asset) }

        private fun page(
            @StringRes title: Int,
            content: Bundle.() -> Unit,
        ) = TextPageFragment().apply { arguments = Bundle().apply { putInt(ARG_TITLE, title) }.apply(content) }
    }
}
