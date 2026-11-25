/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.downloads

import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.Middleware
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Downloads a page starts together are offered one after another instead of replacing each other. */
class DownloadQueueMiddlewareTest {
    // The library's reducer for a new address needs android.net.Uri, which unit tests lack; the queue only reads it.
    private val skipAddressReducer: Middleware<BrowserState, BrowserAction> = { _, next, action ->
        if (action !is ContentAction.UpdateUrlAction) next(action)
    }

    private fun store() =
        BrowserStore(
            BrowserState(tabs = listOf(createTab("https://example.org/", id = "t"))),
            listOf(DownloadQueueMiddleware(later = { it() }), skipAddressReducer),
        )

    // directoryPath given: its default asks android.os.Environment, which unit tests lack.
    private fun download(n: Int) = DownloadState(url = "https://example.org/$n.csv", directoryPath = "/d", id = "d$n")

    private val BrowserStore.download
        get() = state.tabs.single().content.download

    @Test
    fun `downloads that arrive while one waits are offered in turn`() {
        val store = store()
        (0..2).forEach { store.dispatch(ContentAction.UpdateDownloadAction("t", download(it))) }
        assertEquals("d0", store.download?.id)
        store.dispatch(ContentAction.ConsumeDownloadAction("t", "d0"))
        assertEquals("d1", store.download?.id)
        store.dispatch(ContentAction.CancelDownloadAction("t", "d1"))
        assertEquals("d2", store.download?.id)
        store.dispatch(ContentAction.ConsumeDownloadAction("t", "d2"))
        assertNull(store.download)
    }

    @Test
    fun `leaving the page or closing the tab drops the queue`() {
        val store = store()
        (0..1).forEach { store.dispatch(ContentAction.UpdateDownloadAction("t", download(it))) }
        store.dispatch(ContentAction.UpdateUrlAction("t", "https://example.com/"))
        store.dispatch(ContentAction.CancelDownloadAction("t", "d0"))
        assertNull(store.download)

        (0..1).forEach { store.dispatch(ContentAction.UpdateDownloadAction("t", download(it))) }
        store.dispatch(TabListAction.RemoveTabAction("t"))
        store.dispatch(TabListAction.AddTabAction(createTab("https://example.org/", id = "t")))
        store.dispatch(ContentAction.UpdateDownloadAction("t", download(5)))
        store.dispatch(ContentAction.ConsumeDownloadAction("t", "d5"))
        assertNull(store.download)
    }
}
