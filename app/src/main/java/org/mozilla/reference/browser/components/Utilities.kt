/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.components

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.customtabs.CustomTabIntentProcessor
import mozilla.components.feature.intent.processing.IntentProcessor
import mozilla.components.feature.intent.processing.TabIntentProcessor
import mozilla.components.feature.pwa.ManifestStorage
import mozilla.components.feature.pwa.intent.WebAppIntentProcessor
import mozilla.components.feature.search.SearchUseCases
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.CustomTabsUseCases
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import mozilla.components.lib.state.ext.flow
import org.mozilla.reference.browser.ext.components

/** Component group for miscellaneous components. */
@Suppress("LongParameterList")
class Utilities(
    private val context: Context,
    private val store: BrowserStore,
    private val sessionUseCases: SessionUseCases,
    private val searchUseCases: SearchUseCases,
    private val tabsUseCases: TabsUseCases,
    private val customTabsUseCases: CustomTabsUseCases,
    private val applicationScope: CoroutineScope,
) {
    /** Provides intent processing functionality for Progressive Web App and Custom Tab intents. */
    val externalIntentProcessors by lazy {
        listOf(
            CustomTabIntentProcessor(
                customTabsUseCases.add,
                context.resources,
            ),
            WebAppIntentProcessor(
                store,
                customTabsUseCases.addWebApp,
                sessionUseCases.loadUrl,
                ManifestStorage(context),
            ),
        )
    }

    /**
     * Provides intent processing functionality for ACTION_VIEW and ACTION_SEND intents, along with external intent
     * processors.
     */
    val intentProcessors by lazy {
        externalIntentProcessors +
            WaitForSearchEnginesProcessor(
                store,
                applicationScope,
                TabIntentProcessor(
                    tabsUseCases,
                    searchUseCases.newTabSearch,
                    // Without the engine, the processor warms up DNS for the link through the system resolver,
                    // bypassing DNS over HTTPS; with it, it only resolves the DoH provider's host. The engine also
                    // makes it open a speculative connection to each link, which the Gecko config file turns off
                    // (network.http.speculative-parallel-limit = 0).
                    engine = context.components.core.engine,
                    applicationScope = applicationScope,
                ),
            )
    }

    val publicSuffixList by lazy {
        PublicSuffixList(context)
    }
}

/**
 * Search engines load asynchronously at start-up, and a search from a share or search intent that arrives before they
 * are loaded is dropped ("No default search engine"). Such intents wait for the engines; all others pass straight
 * through. Returning true early is safe: this is the last processor, and the intent goes to the browser either way.
 */
private class WaitForSearchEnginesProcessor(
    private val store: BrowserStore,
    private val scope: CoroutineScope,
    private val delegate: IntentProcessor,
) : IntentProcessor {
    override fun process(intent: Intent): Boolean {
        if (store.state.search.complete || intent.action !in SEARCH_ACTIONS) return delegate.process(intent)
        val copy = Intent(intent) // The caller changes the original before forwarding it.
        scope.launch(Dispatchers.Main) {
            // Search loading has no failure state, so do not wait forever; the processor then logs and drops it.
            withTimeoutOrNull(SEARCH_ENGINES_TIMEOUT_MS) { store.flow().first { it.search.complete } }
            delegate.process(copy)
        }
        return true
    }

    private companion object {
        val SEARCH_ACTIONS = setOf(Intent.ACTION_SEND, Intent.ACTION_SEARCH, Intent.ACTION_WEB_SEARCH)
        const val SEARCH_ENGINES_TIMEOUT_MS = 10_000L
    }
}
