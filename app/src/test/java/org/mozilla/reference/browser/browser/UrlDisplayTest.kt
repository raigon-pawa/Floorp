/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the address bar shows for a page's URL. */
class UrlDisplayTest {
    private val privatePage = "data:text/html;base64,PGh0bWw+cHJpdmF0ZTwvaHRtbD4="

    private fun show(url: String) = UrlDisplay.format(url, privatePage)

    @Test
    fun `https is dropped, http stays`() {
        assertEquals("example.com/path?q=1#top", show("https://example.com/path?q=1#top"))
        assertEquals("http://example.com/path", show("http://example.com/path"))
        assertEquals("http://example.com", show("http://example.com/"))
    }

    @Test
    fun `a bare origin loses its trailing slash, a path keeps it`() {
        assertEquals("example.com", show("https://example.com/"))
        assertEquals("example.com/docs/", show("https://example.com/docs/"))
        assertEquals("example.com/?q=1", show("https://example.com/?q=1"))
        assertEquals("example.com/#a", show("https://example.com/#a"))
    }

    @Test
    fun `ports are kept`() {
        assertEquals("example.com:8443", show("https://example.com:8443/"))
        assertEquals("http://127.0.0.1:8080/a.html", show("http://127.0.0.1:8080/a.html"))
        assertEquals("[2001:db8::1]:8443/x", show("https://[2001:db8::1]:8443/x"))
    }

    @Test
    fun `user info cannot pose as the host`() {
        assertEquals("evil.example", show("https://accounts.google.com@evil.example/"))
        assertEquals("http://evil.example/login", show("http://user:pass@evil.example/login"))
        // An @ after the host is part of the path, not user info.
        assertEquals("example.com/@someone", show("https://example.com/@someone"))
    }

    @Test
    fun `a long host keeps the labels that name the site`() {
        val shown = show("https://accounts.google.com.secure-login.verify.evil.example/signin")
        assertEquals("\u2026.verify.evil.example/signin", shown)
        assertTrue(shown.substringBefore('/').length <= UrlDisplay.MAX_HOST_CHARS + 2)
        // The last two labels always stay, however long.
        val longLabel = "a".repeat(40)
        assertEquals("\u2026.$longLabel.example:444", show("https://x.$longLabel.example:444/"))
        // A host that fits is left alone.
        assertEquals("developer.mozilla.org/en-US/", show("https://developer.mozilla.org/en-US/"))
    }

    @Test
    fun `a very long path is kept whole for the view to fade out`() {
        val path = "/" + "segment/".repeat(200)
        assertEquals("example.com$path", show("https://example.com$path"))
    }

    @Test
    fun `IDN hosts are shown as the engine gives them`() {
        assertEquals("xn--bcher-kva.example", show("https://xn--bcher-kva.example/"))
        assertEquals("b\u00fccher.example", show("https://b\u00fccher.example/"))
    }

    @Test
    fun `bidirectional controls are shown escaped`() {
        assertEquals("example.com/%E2%80%AEfdp.exe", show("https://example.com/\u202efdp.exe"))
        assertEquals("http://exa%E2%81%A6mple.com", show("http://exa\u2066mple.com/"))
    }

    @Test
    fun `other schemes are shown as they are`() {
        assertEquals("view-source:https://example.com/", show("view-source:https://example.com/"))
        val options = "moz-extension://0a1b2c3d-1111-2222-3333-444455556666/options.html"
        assertEquals(options, show(options))
        assertEquals("about:config", show("about:config"))
        assertEquals("about:addons", show("about:addons"))
        assertEquals("file:///sdcard/a.html", show("file:///sdcard/a.html"))
        assertEquals("ftp://example.com/", show("ftp://example.com/"))
    }

    @Test
    fun `blank and our private page show as empty, other data URLs do not`() {
        assertEquals("", show("about:blank"))
        assertEquals("", show(privatePage))
        assertTrue(UrlDisplay.isHidden(privatePage, privatePage))
        // Any page can navigate to a data: URL, and to one that only starts like ours.
        val lookalike = "data:text/html;base64,PGh0bWw+ZXZpbDwvaHRtbD4="
        assertEquals(lookalike, show(lookalike))
        assertEquals("$privatePage#", show("$privatePage#"))
        assertFalse(UrlDisplay.isHidden(lookalike, privatePage))
        assertFalse(UrlDisplay.isHidden("about:blank#x", privatePage))
    }

    @Test
    fun `nothing is hidden when the private page URL is unknown`() {
        assertEquals("data:text/html,x", UrlDisplay.format("data:text/html,x", null))
        assertEquals("", UrlDisplay.format("", null))
    }
}
