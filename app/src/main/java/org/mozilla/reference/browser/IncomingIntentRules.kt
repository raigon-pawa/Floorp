/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

/**
 * What another app may ask [IntentReceiverActivity] to load. Kept free of Android types so the rules have JVM tests.
 */
object IncomingIntentRules {
    /** Shared text longer than this is cut before it is scanned for a URL. */
    const val MAX_SHARED_TEXT_LENGTH = 8192

    /** Longest search made from shared text; search engines refuse much longer queries anyway. */
    const val MAX_SEARCH_LENGTH = 1000

    // Letter classes, not (?i): Android's ICU regexes fold case by Unicode, where U+017F passes for an "s". An authority
    // is required, so "http:javascript:alert(1)" does not open a blank tab that shows that address.
    private val webUrl = Regex("[hH][tT][tT][pP][sS]?://[^/?#\\s]+.*")

    // Any scheme as Android's Uri reads it, which is what the processors check. java.net.URI is stricter: it finds no
    // scheme in "data:text/html,<h1>x</h1>" at all.
    private val anyScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /**
     * Returns [url] without surrounding spaces when it is an http or https URL, otherwise null. A URL with spaces or
     * control characters inside is refused: the engine drops tabs and newlines, which could turn it into another scheme
     * ("java\tscript:").
     */
    fun webUrlOrNull(url: String?): String? =
        url?.trim { it <= ' ' }?.takeIf { webUrl.matches(it) && it.none { c -> c <= ' ' || c == '\u007f' } }

    /**
     * What to keep of text shared with the app: the web address [findUrl] finds in it, or the text itself (shortened)
     * for a search when it holds no address. Null when it is blank or names an address of another scheme, which is
     * dropped. [findUrl] is the tab intent processor's own finder, which runs again on whatever is kept.
     */
    fun sharedTextOrNull(text: String?, findUrl: (String) -> String?): String? {
        val scanned = text?.take(MAX_SHARED_TEXT_LENGTH)?.trim()
        if (scanned.isNullOrEmpty()) return null
        val url = findUrl(scanned) ?: return scanned.take(MAX_SEARCH_LENGTH)
        // Without a scheme the processor loads it as http.
        return if (anyScheme.containsMatchIn(url)) webUrlOrNull(url) else url
    }
}
