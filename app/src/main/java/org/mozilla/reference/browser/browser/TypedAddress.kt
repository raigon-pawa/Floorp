/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

/** What Enter in the address bar does with an address that was typed. */
enum class TypedAddress {
    /** The engine loads it, or an app on the device opens it. */
    LOAD,

    /** Nothing can open it ("re:invent", "ac:dc"): it is searched for, as in Firefox. */
    SEARCH;

    companion object {
        // AppLinksUseCases.ENGINE_SUPPORTED_SCHEMES.
        private val engineSchemes =
            setOf(
                "about",
                "blob",
                "data",
                "file",
                "ftp",
                "http",
                "https",
                "moz-extension",
                "moz-safe-about",
                "resource",
                "view-source",
                "ws",
                "wss",
            )

        private val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

        // A host and port, which reads as a scheme ("localhost:3000"); the engine adds http. Mirrors
        // possiblyHostPortRegex in docshell/base/URIFixup.sys.mjs.
        private val hostAndPort = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)*:[0-9]{1,5}([/?#]|$)", RegexOption.IGNORE_CASE)

        /**
         * The action for a typed address, normalised ([url]). [opensInApp] is only asked about a scheme the engine does
         * not know.
         */
        fun of(url: String, opensInApp: () -> Boolean): TypedAddress {
            val scheme = scheme.find(url)?.groupValues?.get(1)?.lowercase()
            return when {
                scheme == null || scheme in engineSchemes || hostAndPort.containsMatchIn(url) -> LOAD
                opensInApp() -> LOAD
                else -> SEARCH
            }
        }
    }
}
