/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mozilla.reference.browser.IncomingIntentRules.MAX_SEARCH_LENGTH
import org.mozilla.reference.browser.IncomingIntentRules.MAX_SHARED_TEXT_LENGTH
import org.mozilla.reference.browser.IncomingIntentRules.sharedTextOrNull
import org.mozilla.reference.browser.IncomingIntentRules.webUrlOrNull

class IncomingIntentRulesTest {
    @Test
    fun `http and https URLs pass in any case`() {
        assertEquals("https://example.org", webUrlOrNull("https://example.org"))
        assertEquals("http://neverssl.com/", webUrlOrNull("http://neverssl.com/"))
        assertEquals("HTTPS://Example.org/a?b#c", webUrlOrNull("HTTPS://Example.org/a?b#c"))
        assertEquals("hTtP://example.org", webUrlOrNull("hTtP://example.org"))
    }

    @Test
    fun `surrounding spaces and control characters are trimmed`() {
        assertEquals("https://example.org", webUrlOrNull("  https://example.org\n"))
        assertEquals("https://example.org", webUrlOrNull("\u0000\u001fhttps://example.org\t"))
    }

    @Test
    fun `other schemes are refused`() {
        listOf(
                "javascript:alert(1)",
                "JavaScript:alert(1)",
                "file:///sdcard/Download/x.html",
                "content://media/external/file/1",
                "data:text/html,<h1>x</h1>",
                "about:config",
                "moz-extension://1234/page.html",
                "resource://gre/modules/x.mjs",
                "view-source:https://example.org",
                "intent://example.org#Intent;scheme=https;end",
                "android-app://com.example/https/example.org",
                "ftp://example.org",
                "example.org",
                "",
            )
            .forEach { assertNull(it, webUrlOrNull(it)) }
        assertNull(webUrlOrNull(null))
    }

    @Test
    fun `look-alikes of http are refused`() {
        assertNull(webUrlOrNull("https:"))
        assertNull(webUrlOrNull("httpſ://example.org"))
        assertNull(webUrlOrNull("https\u0000://example.org"))
        assertNull(webUrlOrNull(" javascript:https://example.org"))
        assertNull(webUrlOrNull("httpsjavascript:alert(1)"))
    }

    @Test
    fun `web URLs without an authority are refused`() {
        assertNull(webUrlOrNull("http:javascript:alert(1)"))
        assertNull(webUrlOrNull("https:example.org"))
        assertNull(webUrlOrNull("https:/example.org"))
        assertNull(webUrlOrNull("https:///example.org"))
        assertNull(webUrlOrNull("https://?javascript:alert(1)"))
    }

    @Test
    fun `spaces inside are kept and control characters refused`() {
        assertEquals("https://example.org/a b", webUrlOrNull("https://example.org/a b"))
        assertNull(webUrlOrNull("https://example.org/\njavascript:alert(1)"))
        assertNull(webUrlOrNull("ht\ttps://example.org"))
        assertNull(webUrlOrNull("https://example.org/\u007f"))
    }

    // WebURLFinder needs the Android runtime, so these tests stand in for it with what it returns on a device.
    private val noUrl: (String) -> String? = { null }

    @Test
    fun `shared text without an address becomes a search`() {
        var scanned: String? = null
        assertEquals(
            "hello world",
            sharedTextOrNull("  hello world ") {
                scanned = it
                null
            },
        )
        assertEquals("hello world", scanned)
    }

    @Test
    fun `a long search is shortened`() {
        val text = "a".repeat(MAX_SEARCH_LENGTH * 50)
        assertEquals("a".repeat(MAX_SEARCH_LENGTH), sharedTextOrNull(text, noUrl))
    }

    @Test
    fun `only the start of very long shared text is scanned`() {
        var scanned = ""
        sharedTextOrNull("a".repeat(MAX_SHARED_TEXT_LENGTH * 2)) {
            scanned = it
            null
        }
        assertEquals(MAX_SHARED_TEXT_LENGTH, scanned.length)
    }

    @Test
    fun `a web address in shared text is kept on its own`() {
        assertEquals(
            "https://example.org/x",
            sharedTextOrNull("Look at this: https://example.org/x") { "https://example.org/x" },
        )
        // The processor loads an address without a scheme as http.
        assertEquals("example.org", sharedTextOrNull("example.org") { "example.org" })
    }

    @Test
    fun `shared addresses of other schemes are dropped`() {
        // The finder already leaves out javascript:, file: and content:, which then become a search.
        listOf("about:config", "data:text/html,<h1>x</h1>", "moz-extension://1234/page.html").forEach { url ->
            assertNull(url, sharedTextOrNull("see $url") { url })
        }
    }

    @Test
    fun `a shared host and port is loaded as http`() {
        assertEquals("http://localhost:3000", sharedTextOrNull("localhost:3000") { "localhost:3000" })
        assertEquals("http://example.com:8080/a", sharedTextOrNull("example.com:8080/a") { "example.com:8080/a" })
    }

    @Test
    fun `a web address after an address of another scheme is kept`() {
        // Like WebURLFinder, which returns the first address with a scheme.
        val finder = { text: String -> listOf("note:see", "mailto:a@b.c", "https://x.org").firstOrNull { it in text } }
        assertEquals("https://x.org", sharedTextOrNull("note:see https://x.org", finder))
        assertEquals("https://x.org", sharedTextOrNull("mailto:a@b.c note:see https://x.org", finder))
        assertNull(sharedTextOrNull("note:see mailto:a@b.c", finder))
    }

    @Test
    fun `blank shared text is dropped`() {
        assertNull(sharedTextOrNull(null, noUrl))
        assertNull(sharedTextOrNull(" \n ", noUrl))
    }
}
