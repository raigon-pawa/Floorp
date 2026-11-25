/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.selector.findTabOrCustomTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

/**
 * Android Components clears a tab's title whenever its address changes by more than the fragment, also when a page only
 * rewrites its address (history.replaceState or pushState, as search result pages do while they load). The document and
 * its title stay the same, so Gecko never sends the title again and the tabs tray showed the bare address for good. The
 * first address change after a page starts loading is the new document's; any later one before the next page start
 * keeps the title.
 */
class KeepTitleMiddleware : Middleware<BrowserState, BrowserAction> {
    // Tabs whose next address change belongs to a new document. Only touched on the store's thread.
    private val newDocument = mutableSetOf<String>()

    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        when (action) {
            is ContentAction.UpdateLoadingStateAction -> if (action.loading) newDocument.add(action.sessionId)
            is ContentAction.UpdateUrlAction -> {
                val title = store.state.findTabOrCustomTab(action.sessionId)?.content?.title
                next(action)
                if (!newDocument.remove(action.sessionId) && !title.isNullOrEmpty()) {
                    val after = store.state.findTabOrCustomTab(action.sessionId)
                    if (after?.content?.title?.isEmpty() == true) {
                        store.dispatch(ContentAction.UpdateTitleAction(action.sessionId, title))
                    }
                }
                return
            }
            else -> Unit
        }
        next(action)
    }
}
