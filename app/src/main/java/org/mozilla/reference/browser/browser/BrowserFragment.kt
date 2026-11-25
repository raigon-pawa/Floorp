/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.thumbnails.BrowserThumbnails
import mozilla.components.browser.toolbar.BrowserToolbar
import mozilla.components.concept.awesomebar.AwesomeBar
import mozilla.components.concept.awesomebar.AwesomeBar.Suggestion
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.awesomebar.AwesomeBarFeature
import mozilla.components.feature.awesomebar.provider.SearchActionProvider
import mozilla.components.feature.awesomebar.provider.SearchSuggestionProvider
import mozilla.components.feature.readerview.view.ReaderViewControlsBar
import mozilla.components.feature.tabs.WindowFeature
import mozilla.components.feature.tabs.toolbar.TabsToolbarFeature
import mozilla.components.feature.toolbar.WebExtensionToolbarFeature
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.ext.requireComponents
import org.mozilla.reference.browser.search.AwesomeBarWrapper
import org.mozilla.reference.browser.settings.Settings
import org.mozilla.reference.browser.tabs.TabsTrayFragment

/** Fragment used for browsing the web within the main app. */
class BrowserFragment : BaseBrowserFragment(), UserInteractionHandler {
    private val thumbnailsFeature = ViewBoundFeatureWrapper<BrowserThumbnails>()
    private val readerViewFeature = ViewBoundFeatureWrapper<ReaderViewIntegration>()
    private val webExtToolbarFeature = ViewBoundFeatureWrapper<WebExtensionToolbarFeature>()
    private val windowFeature = ViewBoundFeatureWrapper<WindowFeature>()

    private val awesomeBar: AwesomeBarWrapper
        get() = requireView().findViewById(R.id.awesomeBar)

    private val toolbar: BrowserToolbar
        get() = requireView().findViewById(R.id.toolbar)

    private val engineView: EngineView
        get() = requireView().findViewById<View>(R.id.engineView) as EngineView

    private val readerViewBar: ReaderViewControlsBar
        get() = requireView().findViewById(R.id.readerViewBar)

    private val readerViewAppearanceButton: FloatingActionButton
        get() = requireView().findViewById(R.id.readerViewAppearanceButton)

    // The Settings switch is gone; ignore a value a profile may still have stored from before.
    override val shouldUseComposeUI: Boolean = false

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        setupAwesomeBar()

        TabsToolbarFeature(
            toolbar = toolbar,
            sessionId = sessionId,
            store = requireComponents.core.store,
            showTabs = ::showTabs,
            lifecycleOwner = this,
        )

        thumbnailsFeature.bind(
            view,
            BrowserThumbnails(
                requireContext(),
                engineView,
                requireComponents.core.store,
            ),
        )

        readerViewFeature.bind(
            view,
            ReaderViewIntegration(
                requireContext(),
                requireComponents.core.engine,
                requireComponents.core.store,
                toolbar,
                readerViewBar,
                readerViewAppearanceButton,
            ),
        )

        webExtToolbarFeature.bind(
            view,
            WebExtensionToolbarFeature(
                toolbar,
                requireContext().components.core.store,
            ),
        )

        windowFeature.bind(
            view,
            WindowFeature(
                store = requireComponents.core.store,
                tabsUseCases = requireComponents.useCases.tabsUseCases,
            ),
        )

        engineView.setDynamicToolbarMaxHeight(resources.getDimensionPixelSize(R.dimen.browser_toolbar_height))
    }

    private fun setupAwesomeBar() {
        val context = requireContext().applicationContext
        val store = requireComponents.core.store
        val defaultSearch = requireComponents.useCases.searchUseCases.defaultSearch
        // Read on every keystroke, so a change in Settings or a switch to a private tab applies at once.
        val networkSuggestions = {
            Settings.isSearchSuggestions(context) && store.state.selectedTab?.content?.private != true
        }
        awesomeBar.addProviders(
            // No engine argument: it would open speculative connections to the search engine.
            SearchSuggestionProvider(
                    store,
                    defaultSearch,
                    fetchClient = requireComponents.core.client,
                    mode = SearchSuggestionProvider.Mode.MULTIPLE_SUGGESTIONS,
                    limit = 5,
                    filterExactMatch = true,
                )
                .onlyWhen(networkSuggestions),
            // Offline "search for ..." row when network suggestions are off.
            SearchActionProvider(store, defaultSearch, showDescription = false).onlyWhen { !networkSuggestions() },
        )

        AwesomeBarFeature(awesomeBar, toolbar, engineView)
            .addSessionProvider(
                resources,
                requireComponents.core.store,
                requireComponents.useCases.tabsUseCases.selectTab,
            )
            .addHistoryProvider(
                requireComponents.core.historyStorage,
                requireComponents.useCases.sessionUseCases.loadUrl,
            )
            .addClipboardProvider(requireContext(), requireComponents.useCases.sessionUseCases.loadUrl)

        awesomeBar.setOnRemoveSuggestionButtonClicked {
            awesomeBar.addHiddenSuggestion(it)
            (it.suggestion as? Suggestion)?.let { s -> deleteHistorySuggestion(s) }
        }
    }

    private fun showTabs() {
        // For now we are performing manual fragment transactions here. Once we can use the new
        // navigation support library we may want to pass navigation graphs around.
        activity?.supportFragmentManager?.beginTransaction()?.apply {
            replace(R.id.container, TabsTrayFragment())
            commit()
        }
    }

    private fun deleteHistorySuggestion(suggestion: Suggestion) {
        lifecycleScope.launch(Dispatchers.IO) {
            suggestion.description?.let {
                requireComponents.core.historyStorage.deleteHistoryMetadataForUrl(it)
            }
        }
    }

    private fun AwesomeBar.SuggestionProvider.onlyWhen(enabled: () -> Boolean): AwesomeBar.SuggestionProvider {
        val provider = this
        return object : AwesomeBar.SuggestionProvider by provider {
            // The awesome bar replaces a provider's earlier results by comparing suggestion.provider with the provider
            // it called, so the suggestions must name this wrapper, not the wrapped provider.
            override suspend fun onInputChanged(text: String) =
                if (enabled()) {
                    provider.onInputChanged(text).map { (it as? Suggestion)?.copy(provider = this) ?: it }
                } else {
                    emptyList()
                }
        }
    }

    override fun onBackPressed(): Boolean = readerViewFeature.onBackPressed() || super.onBackPressed()

    companion object {
        /**
         * Fragments must keep a no-argument constructor so the system can recreate them, which is why the session id
         * travels in the arguments bundle instead of a constructor parameter.
         */
        fun create(sessionId: String? = null) =
            BrowserFragment().apply {
                arguments =
                    Bundle().apply {
                        putSessionId(sessionId)
                    }
            }
    }
}
