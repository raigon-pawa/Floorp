/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import mozilla.components.browser.state.action.TrackingProtectionAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/** The toolbar's shield must say off in the kind of tab where tracking protection is switched off. */
class TrackingProtectionStateMiddlewareTest {
    private fun enabledAfterToggle(policy: TrackingProtectionPolicy?): Pair<Boolean, Boolean> {
        val store =
            BrowserStore(
                BrowserState(
                    tabs =
                        listOf(
                            createTab("https://a.example/", id = "n"),
                            createTab("https://b.example/", id = "p", private = true),
                        )
                ),
                listOf(TrackingProtectionStateMiddleware { policy }),
            )
        store.dispatch(TrackingProtectionAction.ToggleAction("n", true))
        store.dispatch(TrackingProtectionAction.ToggleAction("p", true))
        val tabs = store.state.tabs.associateBy { it.id }
        return tabs.getValue("n").trackingProtection.enabled to tabs.getValue("p").trackingProtection.enabled
    }

    @Test
    fun `private-only policy shows off in normal tabs`() {
        assertEquals(false to true, enabledAfterToggle(TrackingProtectionPolicy.strict().forPrivateSessionsOnly()))
    }

    @Test
    fun `normal-only policy shows off in private tabs`() {
        assertEquals(true to false, enabledAfterToggle(TrackingProtectionPolicy.strict().forRegularSessionsOnly()))
    }

    @Test
    fun `both on, or no policy known, passes the flag through`() {
        assertEquals(true to true, enabledAfterToggle(TrackingProtectionPolicy.strict()))
        assertEquals(true to true, enabledAfterToggle(null))
    }
}
