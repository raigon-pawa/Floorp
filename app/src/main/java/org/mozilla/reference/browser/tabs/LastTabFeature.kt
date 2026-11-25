/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.tabs

import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.findTabOrCustomTabOrSelectedTab
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.feature.UserInteractionHandler
import org.mozilla.reference.browser.browser.StartPage

/**
 * Back on a tab without history: closes a tab opened from another tab and returns to that parent, and stops a site
 * still loading over the start page.
 */
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
            // The tab shown before the link arrived, a private one too; the library would pick a neighbour of the
            // same kind instead.
            val previous = store.state.tabs.filter { it.id != tab.id }.maxByOrNull { it.lastAccess }
            finishActivity()
            removeTabUseCase(tab.id)
            if (tab is TabSessionState && previous != null) store.dispatch(TabListAction.SelectTabAction(previous.id))
            true
        } else if (tab is TabSessionState && tab.content.loading && StartPage.isStartTab(tab)) {
            // A site just picked on the start page has no history entry until it starts to arrive: Back stops it and
            // stays on the start page instead of leaving the app while it loads.
            tab.engineState.engineSession?.stopLoading()
            true
        } else {
            // A tab without a parent stays: Back then leaves the app (BrowserActivity) instead of closing it.
            val hasParent = tab is TabSessionState && tab.parentId != null
            if (hasParent) removeTabUseCase(tab.id, selectParentIfExists = true)
            hasParent
        }
    }
}
