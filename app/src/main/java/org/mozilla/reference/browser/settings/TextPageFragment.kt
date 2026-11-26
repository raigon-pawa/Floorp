/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.reference.browser.R

/**
 * A scrolling page of plain text from a string resource or a bundled asset, such as a licence. Some licence files are
 * hundreds of kilobytes, so the text is read off the main thread and shown one paragraph per recycled row.
 */
class TextPageFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val args = requireArguments()
        val padding = resources.getDimensionPixelSize(R.dimen.archwave_text_page_padding)
        val list =
            RecyclerView(context).apply {
                id = R.id.archwave_text_page
                layoutManager = LinearLayoutManager(context)
                setPadding(0, padding / 2, 0, padding / 2)
                clipToPadding = false
            }
        val asset = args.getString(ARG_ASSET)
        val text = if (asset == null) getString(args.getInt(ARG_TEXT)) else null
        viewLifecycleOwner.lifecycleScope.launch {
            val content =
                text
                    ?: withContext(Dispatchers.IO) {
                        val raw = context.assets.open(asset!!).bufferedReader().use { it.readText() }
                        if (asset.endsWith(".md")) MarkdownText.toPlainText(raw) else raw
                    }
            list.adapter = ParagraphAdapter(content.split(PARAGRAPH_BREAK), padding)
        }
        return list
    }

    /** One selectable text row per paragraph. */
    private class ParagraphAdapter(private val paragraphs: List<String>, private val padding: Int) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view =
                TextView(parent.context).apply {
                    layoutParams =
                        RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                    setPadding(padding, padding / 2, padding, padding / 2)
                    setTextColor(ContextCompat.getColor(context, R.color.aw_text_2))
                    setTextIsSelectable(true)
                }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as TextView).text = paragraphs[position]
        }

        override fun getItemCount() = paragraphs.size
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
        private val PARAGRAPH_BREAK = Regex("""\n\s*\n""")

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
