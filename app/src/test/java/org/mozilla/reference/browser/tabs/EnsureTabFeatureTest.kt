/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.tabs

import kotlinx.coroutines.Dispatchers
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.RestoreCompleteAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.state.Middleware
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.mozilla.reference.browser.browser.StartPage

/** Which tab gets selected, or opened, when none is. */
class EnsureTabFeatureTest {
    // There is no engine to load a new tab's page.
    private val noEngine: Middleware<BrowserState, BrowserAction> = { _, next, action ->
        if (action !is EngineAction) next(action)
    }

    private fun ensure(
        vararg tabs: TabSessionState,
        selected: String? = null,
        restoreComplete: Boolean = true,
    ): BrowserStore {
        val store =
            BrowserStore(
                BrowserState(tabs = tabs.toList(), selectedTabId = selected, restoreComplete = restoreComplete),
                listOf(noEngine),
            )
        EnsureTabFeature(store, TabsUseCases(store)).ensureTab()
        return store
    }

    private fun started(): Pair<BrowserStore, EnsureTabFeature> {
        val store = BrowserStore(BrowserState(restoreComplete = false), listOf(noEngine))
        // Unconfined: the feature reacts on the dispatching thread, so each dispatch has taken effect when it returns.
        val feature = EnsureTabFeature(store, TabsUseCases(store), Dispatchers.Unconfined).apply { start() }
        return store to feature
    }

    @Test
    fun `once started it waits for restore and then opens one start-page tab`() {
        val (store, feature) = started()
        assertEquals(0, store.state.tabs.size)
        store.dispatch(RestoreCompleteAction)
        assertEquals(StartPage.URL, store.state.tabs.single().content.url)
        assertEquals(store.state.tabs.single().id, store.state.selectedTabId)
        // Closing it leaves one new start-page tab, not a loop.
        store.dispatch(TabListAction.RemoveTabAction(store.state.tabs.single().id))
        assertEquals(1, store.state.tabs.size)
        assertEquals(store.state.tabs.single().id, store.state.selectedTabId)
        feature.stop()
    }

    @Test
    fun `tabs restored before restore completes are kept and no start-page tab is added`() {
        val (store, feature) = started()
        store.dispatch(TabListAction.AddTabAction(createTab("https://example.org/", id = "restored")))
        store.dispatch(RestoreCompleteAction)
        assertEquals(listOf("restored"), store.state.tabs.map { it.id })
        assertEquals("restored", store.state.selectedTabId)
        feature.stop()
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
