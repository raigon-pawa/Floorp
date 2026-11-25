/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.text.HtmlCompat
import androidx.core.text.HtmlCompat.FROM_HTML_SEPARATOR_LINE_BREAK_LIST_ITEM
import androidx.fragment.app.Fragment
import org.mozilla.geckoview.BuildConfig.MOZ_APP_BUILDID
import org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.addons.openInBrowserTab

/** About page. Tapping the version lines copies them to the clipboard. */
class AboutFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_about, container, false)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()

        val versionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        val versionInfo =
            getString(R.string.archwave_about_version, versionName) +
                "\n" +
                getString(R.string.archwave_about_engine, MOZ_APP_VERSION, MOZ_APP_BUILDID)
        view.findViewById<TextView>(R.id.version_info).apply {
            text = versionInfo
            setOnClickListener {
                val clipBoard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipBoard.setPrimaryClip(ClipData.newPlainText(versionInfo, versionInfo))
                Toast.makeText(context, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<TextView>(R.id.about_content).text =
            HtmlCompat.fromHtml(
                getString(R.string.about_content, getString(R.string.app_name)),
                FROM_HTML_SEPARATOR_LINE_BREAK_LIST_ITEM,
            )

        view.findViewById<View>(R.id.about_source_code).setOnClickListener {
            openInNewTab(getString(R.string.archwave_source_url))
        }
        view.findViewById<View>(R.id.about_licenses).setOnClickListener { showPage(LicensesFragment()) }
        view.findViewById<View>(R.id.about_connections).setOnClickListener {
            showPage(TextPageFragment.fromText(R.string.archwave_about_connections, R.string.archwave_connections_text))
        }
    }

    override fun onResume() {
        super.onResume()
        // Here rather than at navigation, so the title also comes back after rotation and process death.
        (activity as SettingsFragment.ActionBarUpdater).updateTitle(R.string.preferences_about_page)
    }
}

/** Opens a sub-page of the settings on top of this one. */
internal fun Fragment.showPage(page: Fragment) {
    parentFragmentManager.beginTransaction().replace(R.id.container, page).addToBackStack(null).commit()
}

/**
 * Opens [url] in a new tab of the browser and leaves the settings, so links never leave the app. The tab opened from
 * the one shown before closes on Back and brings that one back, as links from the add-on screens do.
 */
internal fun Fragment.openInNewTab(url: String) {
    requireContext().openInBrowserTab(url)
    requireActivity().finish()
}
