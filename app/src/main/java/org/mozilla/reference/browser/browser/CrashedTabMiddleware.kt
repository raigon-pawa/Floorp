/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import mozilla.components.browser.state.action.ActionWithTab
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.CrashAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.lookupTabIn
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

/**
 * A crashed tab gets no engine session until it is marked as restored, so Android Components silently drops a URL typed
 * into it or a reload. Those are what people try on a broken page: restore the tab first and let the navigation go
 * through.
 */
class CrashedTabMiddleware : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        // shortcut: a URL loaded this way starts before the tab's old history is back, so Back no longer reaches the
        // pages from before the crash; sequence the load after the restore if that matters.
        val navigation = action is EngineAction.LoadUrlAction || action is EngineAction.ReloadAction
        if (navigation && (action as ActionWithTab).lookupTabIn(store)?.engineState?.crashed == true) {
            store.dispatch(CrashAction.RestoreCrashedSessionAction(action.tabId))
        }
        next(action)
    }
}
