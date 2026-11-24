/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.feature.LifecycleAwareFeature

/**
 * Tells [onTabCrashedChanged] whether any tab is waiting to be restored after its content process crashed. It reads
 * the store, so a crash that happened while the owner was stopped is reported when it starts again.
 */
class CrashIntegration(
    private val store: BrowserStore,
    private val onTabCrashedChanged: (Boolean) -> Unit,
) : LifecycleAwareFeature {
    private var scope: CoroutineScope? = null

    override fun start() {
        scope =
            store.flowScoped(dispatcher = Dispatchers.Main) { flow ->
                flow
                    .map { state -> (state.tabs + state.customTabs).any { it.engineState.crashed } }
                    .distinctUntilChanged()
                    .collect { crashed -> onTabCrashedChanged(crashed) }
            }
    }

    override fun stop() {
        scope?.cancel()
    }
}
