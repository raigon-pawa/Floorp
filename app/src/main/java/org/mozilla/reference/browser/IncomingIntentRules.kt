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

    // Letter classes, not (?i): Android's ICU regexes fold case by Unicode, where U+017F passes for an "s". A host is
    // required, so "http:javascript:alert(1)" does not open a blank tab that shows that address.
    private val webUrl = Regex("[hH][tT][tT][pP][sS]?://[^/?#\\s]+.*")

    // Any scheme as Android's Uri reads it, which is what the processors check. java.net.URI is stricter: it finds no
    // scheme in "data:text/html,<h1>x</h1>" at all.
    private val anyScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    // A host and port, which Android's Uri reads as a scheme ("localhost:3000").
    private val hostAndPort = Regex("(localhost|[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+):[0-9]{1,5}([/?#].*)?")

    /**
     * Set by [IntentReceiverActivity] on shared text that must be searched for while the search engines still load; the
     * tab intent processor turns it into the search engine's address once they are there.
     */
    const val EXTRA_SEARCH_TERMS = "org.mozilla.reference.browser.EXTRA_SEARCH_TERMS"

    /** How many addresses of other schemes in shared text are skipped in the search for a web address. */
    private const val MAX_SKIPPED_ADDRESSES = 20

    /**
     * Returns [url] without surrounding spaces when it is an http or https URL, otherwise null. A URL with control
     * characters inside is refused: the engine drops tabs and newlines, which could turn it into another scheme
     * ("java\tscript:"). Spaces stay; the processor encodes those in the path.
     */
    fun webUrlOrNull(url: String?): String? =
        url?.trim { it <= ' ' }?.takeIf { webUrl.matches(it) && it.none { c -> c < ' ' || c == '\u007f' } }

    /**
     * What to keep of text shared with the app: the web address [findUrl] finds in it, or the text itself (shortened)
     * for a search when it holds no address. Addresses of other schemes are skipped; null when it is blank. [findUrl]
     * is the tab intent processor's own finder, which runs again on whatever is kept, so text with such an address
     * ("std::vector", "about:config") cannot be kept for a search: the processor would find and load that address. That
     * text is searched for through [toSearchUrl], the search engine's address for it, instead; null if there is no
     * search engine yet.
     */
    fun sharedTextOrNull(text: String?, findUrl: (String) -> String?, toSearchUrl: (String) -> String?): String? {
        val scanned = text?.take(MAX_SHARED_TEXT_LENGTH)?.trim().orEmpty()
        if (scanned.isEmpty()) return null
        val first = findUrl(scanned) ?: return scanned.take(MAX_SEARCH_LENGTH)
        // Each address that is not kept is cut out before the next search. The finder returns a part of the text, so
        // the text gets shorter every time.
        var rest = scanned
        return generateSequence(first) { skipped ->
                rest = rest.replace(skipped, " ")
                findUrl(rest)
            }
            .take(MAX_SKIPPED_ADDRESSES)
            .firstNotNullOfOrNull(::keptAddressOrNull)
            ?: toSearchUrl(scanned.take(MAX_SEARCH_LENGTH))?.let(::webUrlOrNull)
    }

    private fun keptAddressOrNull(url: String): String? =
        when {
            hostAndPort.matches(url) -> "http://$url"
            // Without a scheme the processor loads it as http.
            !anyScheme.containsMatchIn(url) -> url
            else -> webUrlOrNull(url)
        }
}
