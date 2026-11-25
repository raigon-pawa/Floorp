/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.TrackingProtectionAction
import mozilla.components.browser.state.selector.findTabOrCustomTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

/**
 * Android Components marks tracking protection as on in every tab whenever the [policy] is not "none", also in the kind
 * of tab (normal or private) the policy leaves out, so the toolbar's shield said it was on where it was switched off.
 * The flag is corrected here before it reaches the store.
 */
class TrackingProtectionStateMiddleware(private val policy: () -> TrackingProtectionPolicy?) :
    Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        if (action is TrackingProtectionAction.ToggleAction && action.enabled) {
            val tab = store.state.findTabOrCustomTab(action.tabId)
            val policy = policy()
            if (tab != null && policy != null) {
                val enabled = if (tab.content.private) policy.useForPrivateSessions else policy.useForRegularSessions
                next(action.copy(enabled = enabled))
                return
            }
        }
        next(action)
    }
}
