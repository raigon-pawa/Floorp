/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.content.Context
import android.util.Base64
import org.mozilla.reference.browser.tabs.PrivatePage

/** How the address bar shows a page's address when it is not being edited. */
object UrlDisplay {
    private const val ABOUT_BLANK = "about:blank"
    private const val HTTPS = "https://"
    private const val HTTP = "http://"
    private const val ELLIPSIS = "\u2026"

    /** About what fits in the address bar in portrait; longer hosts lose their leading labels. */
    internal const val MAX_HOST_CHARS = 28

    // Bidirectional controls can make an address read differently from what it is; they never belong in one.
    private val bidiControls = Regex("[\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]")

    @Volatile private var privatePageUrl: String? = null

    /**
     * The URL the engine reports for our own private browsing page: the interceptor serves it as base64 data, which
     * GeckoView turns into exactly this data: URL.
     */
    fun privatePageUrl(context: Context): String =
        privatePageUrl
            ?: run {
                val page = PrivatePage.createPrivateBrowsingPage(context)
                "data:text/html;base64," + Base64.encodeToString(page.toByteArray(), Base64.NO_WRAP)
            }
                .also { privatePageUrl = it }

    /** True for the pages whose address is shown as empty: a blank page and our own private browsing page. */
    fun isHidden(url: String, privatePageUrl: String?): Boolean = url == ABOUT_BLANK || url == privatePageUrl

    /**
     * Returns what to show for [url]. `https://` is dropped but `http://` stays, so an insecure page is obvious; user
     * info (`user@`) is dropped so it cannot pose as the host; a bare origin loses its trailing slash; a very long host
     * keeps its last labels, which name the site. Every other scheme is shown as the engine gives it.
     */
    fun format(url: String, privatePageUrl: String?): String {
        if (isHidden(url, privatePageUrl)) return ""
        val clean = bidiControls.replace(url) { match -> percentEncode(match.value) }

        val scheme =
            when {
                clean.startsWith(HTTPS, ignoreCase = true) -> ""
                clean.startsWith(HTTP, ignoreCase = true) -> clean.take(HTTP.length)
                else -> return clean
            }
        val rest = clean.substring(if (scheme.isEmpty()) HTTPS.length else HTTP.length)
        val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd).substringAfterLast('@')
        val tail = rest.substring(authorityEnd).let { if (it == "/") "" else it }
        return scheme + shortenAuthority(authority) + tail
    }

    private fun shortenAuthority(authority: String): String {
        // An IPv6 literal has no labels to drop.
        if (authority.startsWith("[")) return authority
        val host = authority.substringBefore(':')
        val port = authority.substring(host.length)
        if (host.length <= MAX_HOST_CHARS) return authority

        val labels = host.split('.')
        var kept = labels.takeLast(2).joinToString(".")
        for (label in labels.dropLast(2).asReversed()) {
            if (label.length + 1 + kept.length > MAX_HOST_CHARS) break
            kept = "$label.$kept"
        }
        return "$ELLIPSIS.$kept$port"
    }

    private fun percentEncode(text: String): String =
        text.toByteArray(Charsets.UTF_8).joinToString("") { "%%%02X".format(it.toUByte().toInt()) }
}
