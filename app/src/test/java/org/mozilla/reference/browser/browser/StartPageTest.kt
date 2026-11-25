/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.createTab
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the start page covers the engine view. */
class StartPageTest {
    private fun shown(tab: TabSessionState?, restoreComplete: Boolean = true, vararg others: TabSessionState) =
        StartPage.isShown(
            BrowserState(
                tabs = listOfNotNull(tab) + others,
                selectedTabId = tab?.id,
                restoreComplete = restoreComplete,
            )
        )

    private fun blank(loading: Boolean = false) =
        createTab(StartPage.URL).let { it.copy(content = it.content.copy(loading = loading)) }

    @Test fun `a normal tab at the blank page shows it`() = assertTrue(shown(blank()))

    @Test fun `a page does not`() = assertFalse(shown(createTab("https://example.com/")))

    @Test fun `it stays while the tab starts loading a site`() = assertTrue(shown(blank(loading = true)))

    @Test fun `plain about blank shows it too`() = assertTrue(shown(createTab("about:blank")))

    @Test fun `a private blank page does not`() = assertFalse(shown(createTab(StartPage.URL, private = true)))

    @Test
    fun `a blank page a page opened does not`() =
        assertFalse(
            shown(createTab("about:blank", parentId = "opener"), true, createTab("https://example.com/", id = "opener"))
        )

    @Test
    fun `no selected tab shows it once restore has finished`() {
        assertTrue(shown(null, restoreComplete = true))
        // A restored page would flash it otherwise.
        assertFalse(shown(null, restoreComplete = false))
    }

    @Test
    fun `no selected tab with only private tabs shows it`() =
        assertTrue(shown(null, true, createTab("https://example.com/", private = true)))

    @Test
    fun `no selected tab with a normal tab left does not, as that tab is about to be selected`() =
        assertFalse(shown(null, true, createTab("https://example.com/")))
}
