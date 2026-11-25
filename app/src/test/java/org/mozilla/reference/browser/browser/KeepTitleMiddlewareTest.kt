/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.Middleware
import org.junit.Assert.assertEquals
import org.junit.Test

/** A page that rewrites its own address keeps its title; a new page load does not inherit the old one. */
class KeepTitleMiddlewareTest {
    // What the library's reducer does with a new address (it needs android.net.Uri, which unit tests lack).
    private val clearsTitle: Middleware<BrowserState, BrowserAction> = { store, next, action ->
        if (action is ContentAction.UpdateUrlAction) {
            store.dispatch(ContentAction.UpdateTitleAction(action.sessionId, ""))
        } else {
            next(action)
        }
    }

    private fun store(): BrowserStore {
        val tab = createTab("https://duckduckgo.com/?q=linux", id = "t", title = "linux at DuckDuckGo")
        return BrowserStore(BrowserState(tabs = listOf(tab)), listOf(KeepTitleMiddleware(), clearsTitle))
    }

    private val BrowserStore.title
        get() = state.tabs.single().content.title

    @Test
    fun `address rewritten by the page keeps the title`() {
        val store = store()
        store.dispatch(ContentAction.UpdateUrlAction("t", "https://duckduckgo.com/?q=linux&ia=web"))
        assertEquals("linux at DuckDuckGo", store.title)
    }

    @Test
    fun `a new page gets no old title, and keeps its own through a rewrite`() {
        val store = store()
        store.dispatch(ContentAction.UpdateLoadingStateAction("t", true))
        store.dispatch(ContentAction.UpdateUrlAction("t", "https://duckduckgo.com/?q=bsd"))
        assertEquals("", store.title)
        store.dispatch(ContentAction.UpdateTitleAction("t", "bsd at DuckDuckGo"))
        store.dispatch(ContentAction.UpdateUrlAction("t", "https://duckduckgo.com/?q=bsd&ia=web"))
        assertEquals("bsd at DuckDuckGo", store.title)
    }
}
