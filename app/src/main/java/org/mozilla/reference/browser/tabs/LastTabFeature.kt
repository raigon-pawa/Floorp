/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.tabs

import mozilla.components.browser.state.selector.findTabOrCustomTabOrSelectedTab
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.feature.UserInteractionHandler

/** Back on a tab without history: closes a tab opened from another tab and returns to that parent. */
class LastTabFeature(
    private val store: BrowserStore,
    private val tabId: String? = null,
    private val removeTabUseCase: TabsUseCases.RemoveTabUseCase,
    private val finishActivity: () -> Unit,
) : LifecycleAwareFeature, UserInteractionHandler {
    override fun start() = Unit

    override fun stop() = Unit

    /**
     * Removes the session if it was opened by an ACTION_VIEW intent or if it has a parent session and no more history;
     * returns false, changing nothing, for any other tab.
     */
    override fun onBackPressed(): Boolean {
        val tab = store.state.findTabOrCustomTabOrSelectedTab(tabId) ?: return false
        val isExternalOrCustomTab =
            tab.source is SessionState.Source.External || tab.source is SessionState.Source.Internal.CustomTab

        return if (isExternalOrCustomTab && !tab.restored) {
            finishActivity()
            removeTabUseCase(tab.id)
            true
        } else {
            // A tab without a parent stays: Back then leaves the app (BrowserActivity) instead of closing it.
            val hasParent = tab is TabSessionState && tab.parentId != null
            if (hasParent) removeTabUseCase(tab.id, selectParentIfExists = true)
            hasParent
        }
    }
}
