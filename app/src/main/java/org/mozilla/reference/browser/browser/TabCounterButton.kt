/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import mozilla.components.browser.state.selector.getNormalOrPrivateTabs
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.tabs.toolbar.TabCounterToolbarButton
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.ui.tabcounter.TabCounterView

/**
 * The toolbar's tab counter, with the private mask and count following every switch between a normal and a private tab.
 * The library sets the mask only when the view is made, and recounts only when the count changes, so a switch without a
 * trip through the tabs tray kept the other mode's mask (and count, when both had as many tabs).
 */
class TabCounterButton(
    private val lifecycleOwner: LifecycleOwner,
    private val store: BrowserStore,
    showTabs: () -> Unit,
) : TabCounterToolbarButton(lifecycleOwner = lifecycleOwner, showTabs = showTabs, store = store) {
    override fun createView(parent: ViewGroup): View {
        val view = super.createView(parent)
        val counter = view as? TabCounterView ?: return view
        store.flowScoped(owner = lifecycleOwner, dispatcher = Dispatchers.Main) { flow ->
            flow
                .map { it.selectedTab?.content?.private == true }
                .distinctUntilChanged()
                .collect { private ->
                    counter.toggleCounterMask(private)
                    counter.setCount(store.state.getNormalOrPrivateTabs(private).size)
                    counter.updateContentDescription(private)
                }
        }
        return view
    }
}
