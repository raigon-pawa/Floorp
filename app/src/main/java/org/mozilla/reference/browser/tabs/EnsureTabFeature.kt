/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.tabs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.feature.LifecycleAwareFeature
import org.mozilla.reference.browser.browser.StartPage

/**
 * Keeps a tab selected in the browser: whenever none is (first launch, the last tab closed, Close all tabs, the last
 * private tab closed), selects the most recently used normal tab or, without one, opens a start-page tab. Only bound by
 * the main browser screen, so custom tabs and web apps never get a tab from it, and only while that screen is started.
 */
class EnsureTabFeature(
    private val store: BrowserStore,
    private val tabsUseCases: TabsUseCases,
) : LifecycleAwareFeature {
    private var scope: CoroutineScope? = null

    override fun start() {
        scope =
            store.flowScoped(dispatcher = Dispatchers.Main) { flow ->
                flow.map(::needsTab).distinctUntilChanged().filter { it }.collect { ensureTab() }
            }
    }

    override fun stop() {
        scope?.cancel()
    }

    /**
     * Selects or opens a tab if none is selected. Nothing happens before restore has finished: the restored tabs would
     * arrive after a start-page tab opened for an empty store.
     */
    internal fun ensureTab() {
        val state = store.state
        if (!needsTab(state)) return
        val recent = state.normalTabs.maxByOrNull { it.lastAccess }
        if (recent != null) {
            tabsUseCases.selectTab(recent.id)
        } else {
            tabsUseCases.addTab(StartPage.URL, selectTab = true)
        }
    }

    private fun needsTab(state: BrowserState) = state.restoreComplete && state.selectedTab == null
}
