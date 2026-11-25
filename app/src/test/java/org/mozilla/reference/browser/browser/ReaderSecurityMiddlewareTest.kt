/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.ReaderAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.SecurityInfo
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reader view keeps the article's security state; outside it, every update applies. */
class ReaderSecurityMiddlewareTest {
    private fun store(): BrowserStore {
        val tab = createTab("https://en.wikipedia.org/wiki/Arch_Linux", id = "t")
        return BrowserStore(BrowserState(tabs = listOf(tab)), listOf(ReaderSecurityMiddleware())).apply {
            dispatch(ContentAction.UpdateSecurityInfoAction("t", SecurityInfo.Secure()))
        }
    }

    private val BrowserStore.secure
        get() = state.tabs.single().content.securityInfo.isSecure

    @Test
    fun `the reader page keeps the article's secure state`() {
        val store = store()
        store.dispatch(ReaderAction.UpdateReaderActiveAction("t", true))
        store.dispatch(ContentAction.UpdateSecurityInfoAction("t", SecurityInfo.Insecure()))
        assertTrue(store.secure)
    }

    @Test
    fun `outside reader view an insecure page shows as insecure`() {
        val store = store()
        store.dispatch(ReaderAction.UpdateReaderActiveAction("t", true))
        store.dispatch(ReaderAction.UpdateReaderActiveAction("t", false))
        store.dispatch(ContentAction.UpdateSecurityInfoAction("t", SecurityInfo.Insecure()))
        assertFalse(store.secure)
    }
}
