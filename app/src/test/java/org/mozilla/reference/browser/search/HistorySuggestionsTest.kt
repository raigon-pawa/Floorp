/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.search

import mozilla.components.concept.storage.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

/** One row per page in history suggestions, whatever HTTPS upgrade happened on the way. */
class HistorySuggestionsTest {
    private fun result(url: String, title: String? = null) = SearchResult(url, url, 1, title)

    @Test
    fun `the plain-HTTP hop of an upgraded page is dropped`() {
        val https = result("https://en.wikipedia.org/wiki/Koala", "Koala - Wikipedia")
        assertEquals(
            listOf(https),
            withoutHttpsUpgradeHops(listOf(result("http://en.wikipedia.org/wiki/Koala"), https)),
        )
    }

    @Test
    fun `an http page without an https twin stays`() {
        val results = listOf(result("http://neverssl.com/"), result("https://example.com/"))
        assertEquals(results, withoutHttpsUpgradeHops(results))
    }
}
