/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class TypedAddressTest {
    private val noApp = { false }

    @Test
    fun `an unknown scheme nothing opens is searched for`() {
        listOf("re:invent", "ac:dc", "RE:Invent", "foo://bar").forEach {
            assertEquals(it, TypedAddress.SEARCH, TypedAddress.of(it, noApp))
        }
    }

    @Test
    fun `an unknown scheme an app opens is loaded`() {
        assertEquals(TypedAddress.LOAD, TypedAddress.of("mailto:someone@example.org") { true })
    }

    @Test
    fun `engine schemes and hosts with a port are loaded without asking for apps`() {
        val asked = { error("must not be asked") }
        listOf(
                "http://example.org/",
                "HTTPS://example.org/",
                "about:config",
                "view-source:https://example.org/",
                "moz-extension://1234/page.html",
                "localhost:3000",
                "example.com:443/a",
                "192.168.1.1:8080",
            )
            .forEach { assertEquals(it, TypedAddress.LOAD, TypedAddress.of(it, asked)) }
    }

    @Test
    fun `addresses the engine refuses are refused before anything else`() {
        val asked = { error("must not be asked") }
        listOf("file:///sdcard/", "FILE:///sdcard/", "resource://gre/", "javascript:alert(1)", "fido:x").forEach {
            assertEquals(it, TypedAddress.REFUSE, TypedAddress.of(it, asked))
        }
    }
}
