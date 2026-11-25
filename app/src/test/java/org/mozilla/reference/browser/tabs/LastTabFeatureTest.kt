/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.tabs

import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.tabs.TabsUseCases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Back on a tab without history: which tabs close and when the app is left alone to go to the background. */
class LastTabFeatureTest {
    private val parent = createTab("https://example.com/", id = "parent")
    private var finished = false

    private fun backOn(vararg tabs: TabSessionState, selected: String): Pair<BrowserStore, Boolean> {
        val store = BrowserStore(BrowserState(tabs = tabs.toList(), selectedTabId = selected))
        val feature = LastTabFeature(store, removeTabUseCase = TabsUseCases(store).removeTab) { finished = true }
        return store to feature.onBackPressed()
    }

    @Test
    fun `a tab without a parent stays and Back is left to the activity`() {
        val (store, handled) = backOn(parent, selected = "parent")
        assertFalse(handled)
        assertEquals(listOf("parent"), store.state.tabs.map { it.id })
        assertEquals("parent", store.state.selectedTabId)
        assertFalse(finished)
    }

    @Test
    fun `a tab opened from another tab closes and selects its parent`() {
        val other = createTab("https://example.org/", id = "other")
        val child = createTab("https://example.com/child", id = "child", parent = parent)
        val (store, handled) = backOn(parent, other, child, selected = "child")
        assertTrue(handled)
        assertEquals(listOf("parent", "other"), store.state.tabs.map { it.id })
        assertEquals("parent", store.state.selectedTabId)
        assertFalse(finished)
    }

    @Test
    fun `a tab from an outside link closes and finishes the activity`() {
        val external =
            createTab("https://example.com/", id = "external", source = SessionState.Source.External.ActionView(null))
        val (store, handled) = backOn(parent, external, selected = "external")
        assertTrue(handled)
        assertEquals(listOf("parent"), store.state.tabs.map { it.id })
        assertTrue(finished)
    }

    @Test
    fun `a restored tab from an outside link is treated like any other`() {
        val external =
            createTab(
                "https://example.com/",
                id = "external",
                source = SessionState.Source.External.ActionView(null),
                restored = true,
            )
        val (store, handled) = backOn(parent, external, selected = "external")
        assertFalse(handled)
        assertEquals(2, store.state.tabs.size)
        assertFalse(finished)
    }
}
