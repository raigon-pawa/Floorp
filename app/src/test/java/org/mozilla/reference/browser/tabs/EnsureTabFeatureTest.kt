/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.tabs

import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.tabs.TabsUseCases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.mozilla.reference.browser.browser.StartPage

/** Which tab gets selected, or opened, when none is. */
class EnsureTabFeatureTest {
    private fun ensure(
        vararg tabs: TabSessionState,
        selected: String? = null,
        restoreComplete: Boolean = true,
    ): BrowserStore {
        val store =
            BrowserStore(
                BrowserState(tabs = tabs.toList(), selectedTabId = selected, restoreComplete = restoreComplete),
                // There is no engine to load the new tab's page.
                listOf { _, next, action -> if (action !is EngineAction) next(action) },
            )
        EnsureTabFeature(store, TabsUseCases(store)).ensureTab()
        return store
    }

    @Test
    fun `with no tab at all a start-page tab opens and is selected`() {
        val store = ensure()
        val tab = store.state.tabs.single()
        assertEquals(StartPage.URL, tab.content.url)
        assertFalse(tab.content.private)
        assertEquals(tab.id, store.state.selectedTabId)
    }

    @Test
    fun `nothing happens before restore has finished`() {
        val store = ensure(restoreComplete = false)
        assertEquals(0, store.state.tabs.size)
        assertNull(store.state.selectedTabId)
    }

    @Test
    fun `the most recently used normal tab is selected`() {
        val older = createTab("https://example.com/", id = "older", lastAccess = 1)
        val recent = createTab("https://example.org/", id = "recent", lastAccess = 2)
        val store = ensure(older, recent)
        assertEquals("recent", store.state.selectedTabId)
        assertEquals(2, store.state.tabs.size)
    }

    @Test
    fun `private tabs are never picked and a start-page tab opens beside them`() {
        val store = ensure(createTab("https://example.com/", id = "private", private = true, lastAccess = 9))
        assertEquals(2, store.state.tabs.size)
        val selected = store.state.tabs.single { it.id == store.state.selectedTabId }
        assertEquals(StartPage.URL, selected.content.url)
        assertFalse(selected.content.private)
    }

    @Test
    fun `a selected tab is left alone`() {
        val store = ensure(createTab("https://example.com/", id = "private", private = true), selected = "private")
        assertEquals("private", store.state.selectedTabId)
        assertEquals(1, store.state.tabs.size)
    }
}
