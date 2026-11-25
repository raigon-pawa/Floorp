/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import mozilla.components.concept.engine.Engine.BrowsingData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks which engine data each clear-browsing-data choice removes. */
class BrowsingDataCleanerTest {
    private fun flags(vararg items: ClearableData) = BrowsingDataCleaner.engineFlags(items.toSet())

    @Test
    fun `cookies clear cookies, site storage and logins to sites, nothing else`() {
        assertEquals(
            BrowsingData.COOKIES or BrowsingData.DOM_STORAGES or BrowsingData.AUTH_SESSIONS,
            flags(ClearableData.COOKIES),
        )
    }

    @Test
    fun `cache clears the network and image caches`() {
        assertEquals(BrowsingData.NETWORK_CACHE or BrowsingData.IMAGE_CACHE, flags(ClearableData.CACHE))
    }

    @Test
    fun `app-side choices need no engine data`() {
        assertEquals(
            0,
            flags(ClearableData.TABS, ClearableData.HISTORY, ClearableData.PERMISSIONS, ClearableData.DOWNLOADS),
        )
    }

    @Test
    fun `everything together never reaches the engine-wide flags`() {
        val all = flags(*ClearableData.entries.toTypedArray())
        // ALL would also clear extension data; permissions are cleared per site instead.
        assertEquals(0, all and BrowsingData.ALL)
        assertEquals(0, all and BrowsingData.PERMISSIONS)
        assertEquals(0, all and (BrowsingData.ALL_SITE_SETTINGS - BrowsingData.PERMISSIONS))
    }

    @Test
    fun `only open tabs are left unchecked by default`() {
        assertEquals(listOf(ClearableData.TABS), ClearableData.entries.filterNot { it.checkedByDefault })
    }

    @Test
    fun `extensions and internal pages keep their permissions`() {
        assertTrue(BrowsingDataCleaner.isWebOrigin("https://example.org"))
        assertTrue(BrowsingDataCleaner.isWebOrigin("http://localhost:8765"))
        assertFalse(BrowsingDataCleaner.isWebOrigin("moz-extension://8b1f3c2e-0000-4000-8000-000000000000"))
        assertFalse(BrowsingDataCleaner.isWebOrigin("about:config"))
    }
}
