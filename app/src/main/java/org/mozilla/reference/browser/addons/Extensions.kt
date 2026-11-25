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

/** Whether [url] is an ordinary web address, the only kind that add-on metadata may open. */
internal fun isWebUrl(url: String?): Boolean = url != null && (url.startsWith("https://") || url.startsWith("http://"))

/**
 * Opens [url] in a new tab of this app and brings the browser to the front, closing the add-on screens above it. The
 * current tab becomes the parent, so Back from the new tab returns to it.
 */
internal fun Context.openInBrowserTab(url: String) {
    components.useCases.tabsUseCases.addTab(
        url,
        selectTab = true,
        parentId = components.core.store.state.selectedTabId,
    )
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
