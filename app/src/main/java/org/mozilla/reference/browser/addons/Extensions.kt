/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.addons

import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import androidx.fragment.app.Fragment
import java.text.NumberFormat
import java.util.Locale
import mozilla.components.browser.state.selector.selectedTab
import org.mozilla.reference.browser.BrowserActivity
import org.mozilla.reference.browser.ext.components

internal fun getFormattedAmount(amount: Int): String =
    NumberFormat.getNumberInstance(Locale.getDefault()).format(amount)

/**
 * Run the [block] only if the [Fragment] is attached.
 *
 * @param block A callback to be executed if the container [Fragment] is attached.
 */
internal inline fun Fragment.runIfFragmentIsAttached(block: () -> Unit) {
    context?.let {
        block()
    }
}

/** Mozilla's explanation of extension permissions, opened by every "Learn more" about them. */
internal const val PERMISSIONS_LEARN_MORE_URL =
    "https://support.mozilla.org/kb/permission-request-messages-firefox-extensions"

/** Whether [url] is an ordinary web address, the only kind that add-on metadata may open. */
internal fun isWebUrl(url: String?): Boolean = url != null && (url.startsWith("https://") || url.startsWith("http://"))

/**
 * Opens [url] in a new normal tab of this app and brings the browser to the front, closing the add-on screens above it.
 * A normal current tab becomes the parent, so Back from the new tab returns to it; a private one does not, so closing
 * the new tab cannot lead into private browsing. The page is not loaded as if the parent opened it: a web page or the
 * start page may not link to about:license, which then showed "Cannot Complete Request".
 */
internal fun Context.openInBrowserTab(url: String) {
    val tabId =
        components.useCases.tabsUseCases.addTab(
            url,
            selectTab = true,
            startLoading = false,
            parentId = components.core.store.state.selectedTab?.takeUnless { it.content.private }?.id,
        )
    components.useCases.sessionUseCases.loadUrl(url, sessionId = tabId)
    startActivity(Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Makes the web links in this text open in a tab of this app instead of another app; drops all other links. */
internal fun Spanned.withLinksInBrowserTabs(): Spanned =
    SpannableString(this).apply {
        getSpans(0, length, URLSpan::class.java).forEach { span ->
            val start = getSpanStart(span)
            val end = getSpanEnd(span)
            val flags = getSpanFlags(span)
            removeSpan(span)
            if (isWebUrl(span.url)) {
                setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) = widget.context.openInBrowserTab(span.url)
                    },
                    start,
                    end,
                    flags,
                )
            }
        }
    }
