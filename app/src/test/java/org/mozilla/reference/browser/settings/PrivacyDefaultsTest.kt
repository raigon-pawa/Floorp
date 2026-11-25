/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import mozilla.components.concept.engine.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Checks the privacy defaults in archwave_privacy.xml and the mapping of stored values to engine settings. */
class PrivacyDefaultsTest {
    private val res = File("src/main/res")
    private val privacy = parse("values/archwave_privacy.xml")

    @Test
    fun `stored resolver maps to DoH first with fallback, empty to off`() {
        assertEquals(Engine.DohSettingsMode.INCREASED, Settings.dohModeFor("https://dns.quad9.net/dns-query"))
        assertEquals(Engine.DohSettingsMode.OFF, Settings.dohModeFor(""))
    }

    @Test
    fun `HTTPS-only switch maps to all tabs or none`() {
        assertEquals(Engine.HttpsOnlyMode.ENABLED, Settings.httpsOnlyModeFor(true))
        assertEquals(Engine.HttpsOnlyMode.DISABLED, Settings.httpsOnlyModeFor(false))
    }

    @Test
    fun `defaults match the product decisions`() {
        assertEquals("true", resource("bool", "pref_default_tracking_protection_normal"))
        assertEquals("true", resource("bool", "pref_default_tracking_protection_private"))
        assertEquals("true", resource("bool", "pref_default_https_only"))
        assertEquals("true", resource("bool", "pref_default_global_privacy_control"))
        assertEquals("false", resource("bool", "pref_default_search_suggestions"))
        assertEquals("https://dns.quad9.net/dns-query", resolve(resource("string", "pref_default_doh_provider")))
    }

    @Test
    fun `DoH choices are Quad9, Cloudflare, Mullvad and off`() {
        val values = array("doh_provider_values").map(::resolve)
        assertEquals(
            listOf(
                "https://dns.quad9.net/dns-query",
                "https://cloudflare-dns.com/dns-query",
                "https://dns.mullvad.net/dns-query",
                "",
            ),
            values,
        )
        assertEquals(values.size, array("doh_provider_entries").size)
        assertTrue(values.contains(resolve(resource("string", "pref_default_doh_provider"))))
    }

    @Test
    fun `every privacy row takes its default from a resource, never a literal`() {
        val rows = parse("xml/privacy_preferences.xml").getElementsByTagName("*")
        val defaults =
            (0 until rows.length)
                .map { rows.item(it) as Element }
                .filter { it.hasAttribute("android:defaultValue") }
                .associate { it.getAttribute("android:key") to it.getAttribute("android:defaultValue") }
        assertEquals(
            mapOf(
                "@string/pref_key_tracking_protection_normal" to "@bool/pref_default_tracking_protection_normal",
                "@string/pref_key_tracking_protection_private" to "@bool/pref_default_tracking_protection_private",
                "@string/pref_key_https_only" to "@bool/pref_default_https_only",
                "@string/pref_key_doh_provider" to "@string/pref_default_doh_provider",
                "@string/pref_key_global_privacy_control" to "@bool/pref_default_global_privacy_control",
                "@string/pref_key_search_suggestions" to "@bool/pref_default_search_suggestions",
            ),
            defaults,
        )
    }

    private fun parse(path: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, path)).documentElement

    private fun elements(tag: String): List<Element> {
        val nodes = privacy.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun resource(
        type: String,
        name: String,
    ): String = elements(type).single { it.getAttribute("name") == name }.textContent.trim()

    private fun array(name: String): List<String> {
        val items = elements("string-array").single { it.getAttribute("name") == name }.getElementsByTagName("item")
        return (0 until items.length).map { items.item(it).textContent.trim() }
    }

    private fun resolve(value: String): String =
        if (value.startsWith("@string/")) resource("string", value.removePrefix("@string/")) else value
}
