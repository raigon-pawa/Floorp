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
 * A crashed tab gets no engine session until it is marked as restored. Until then Android Components keeps
 * re-dispatching whatever the user does to it on the main thread: Back, a reload or a typed URL do nothing while the
 * app spins, and they all fire at once when the tab is restored later. Those are what people try on a broken page:
 * restore the tab first and let the action go through.
 */
class CrashedTabMiddleware : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        // shortcut: a URL loaded this way starts before the tab's old history is back, so Back no longer reaches the
        // pages from before the crash; sequence the load after the restore if that matters.
        if (action.needsEngineSession() && (action as ActionWithTab).lookupTabIn(store)?.engineState?.crashed == true) {
            store.dispatch(CrashAction.RestoreCrashedSessionAction(action.tabId))
        }
        next(action)
    }
}

/** The actions the app can send that EngineDelegateMiddleware hands to the tab's engine session. */
private fun BrowserAction.needsEngineSession() =
    when (this) {
        is EngineAction.LoadUrlAction,
        is EngineAction.LoadDataAction,
        is EngineAction.ReloadAction,
        is EngineAction.GoBackAction,
        is EngineAction.GoForwardAction,
        is EngineAction.GoToHistoryIndexAction,
        is EngineAction.ToggleDesktopModeAction,
        is EngineAction.ExitFullScreenModeAction -> true
        else -> false
    }
