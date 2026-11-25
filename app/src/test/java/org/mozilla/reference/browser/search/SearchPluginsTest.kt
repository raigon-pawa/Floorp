/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.search

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every bundled engine is the app's own copy, without Mozilla's partner and client codes: an engine missing from the
 * app's assets falls back to the library's copy, which carries them.
 */
class SearchPluginsTest {
    private val partnerCodes = listOf("firefox", "MOZ", "Mozilla-search", "fpas")

    @Test
    fun `bundled engines carry no partner codes`() {
        val list = File("src/main/assets/search/list.json").readText()
        val ids = Regex("\"visibleDefaultEngines\":\\s*\\[([^]]*)]").find(list)!!.groupValues[1]
        val engines = Regex("\"([^\"]+)\"").findAll(ids).map { it.groupValues[1] }.toList()
        assertTrue(engines.isNotEmpty())
        engines.forEach { id ->
            val plugin = File("src/main/assets/searchplugins/$id.xml")
            assertTrue("$id has no copy in the app's assets", plugin.isFile)
            val urls = Regex("<Url[^>]*>(.*?</Url>)?", RegexOption.DOT_MATCHES_ALL).findAll(plugin.readText())
            urls.forEach { url ->
                partnerCodes.forEach { assertFalse("$id: $it in ${url.value}", url.value.contains(it)) }
            }
        }
    }
}
