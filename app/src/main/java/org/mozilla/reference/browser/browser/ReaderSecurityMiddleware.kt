/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

/**
 * Reader view shows an article in a page of the built-in reader extension, which Gecko reports as not secure, while the
 * toolbar keeps showing the article's own address: an HTTPS article got the crossed "not secure" shield. While reader
 * view is on, the tab keeps the security state of the page the article came from.
 */
class ReaderSecurityMiddleware : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        if (
            action is ContentAction.UpdateSecurityInfoAction &&
                store.state.findTab(action.sessionId)?.readerState?.active == true
        ) {
            return
        }
        next(action)
    }
}
