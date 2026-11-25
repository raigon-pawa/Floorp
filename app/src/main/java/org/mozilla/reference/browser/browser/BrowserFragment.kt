/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.thumbnails.BrowserThumbnails
import mozilla.components.browser.toolbar.BrowserToolbar
import mozilla.components.concept.awesomebar.AwesomeBar
import mozilla.components.concept.awesomebar.AwesomeBar.Suggestion
import mozilla.components.concept.base.images.ImageSaveRequest
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.awesomebar.AwesomeBarFeature
import mozilla.components.feature.awesomebar.R as awesomebarR
import mozilla.components.feature.awesomebar.provider.SearchActionProvider
import mozilla.components.feature.awesomebar.provider.SearchSuggestionProvider
import mozilla.components.feature.awesomebar.provider.SessionSuggestionProvider
import mozilla.components.feature.readerview.view.ReaderViewControlsBar
import mozilla.components.feature.tabs.toolbar.TabsToolbarFeature
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.ktx.android.content.isOSOnLowMemory
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.ext.requireComponents
import org.mozilla.reference.browser.search.AwesomeBarWrapper
import org.mozilla.reference.browser.settings.Settings
import org.mozilla.reference.browser.tabs.EnsureTabFeature
import org.mozilla.reference.browser.tabs.TabsTrayFragment

/** Fragment used for browsing the web within the main app. */
class BrowserFragment : BaseBrowserFragment(), UserInteractionHandler {
    private val thumbnailsFeature = ViewBoundFeatureWrapper<BrowserThumbnails>()
    private val readerViewFeature = ViewBoundFeatureWrapper<ReaderViewIntegration>()
    private val startPageIntegration = ViewBoundFeatureWrapper<StartPageIntegration>()
    private val ensureTabFeature = ViewBoundFeatureWrapper<EnsureTabFeature>()
    private var showTabsJob: Job? = null

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

        startPageIntegration.bind(
            view,
            StartPageIntegration(
                view.findViewById(R.id.startPage),
                engineView.asView(),
                requireComponents.core.store,
                toolbar,
                requireComponents.useCases.sessionUseCases.loadUrl,
            ),
        )
        ensureTabFeature.bind(
            view,
            EnsureTabFeature(
                requireComponents.core.store,
                requireComponents.useCases.tabsUseCases,
                pendingIntentTabs = requireComponents.utils.pendingIntentTabs,
            ),
        )

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
    }

    private fun setupAwesomeBar() {
        // The toolbar is drawn over the bottom of the list; keep the last suggestion clear of it.
        awesomeBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = resources.getDimensionPixelSize(R.dimen.browser_toolbar_height)
        }
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
            // The typed text as a "search for ..." row, always: the suggestion provider leaves it out
            // (filterExactMatch)
            // and shows nothing when its request fails.
            SearchActionProvider(store, defaultSearch, showDescription = false),
            // Open tabs, but not start-page tabs: their blank address matches most single letters.
            SessionSuggestionProvider(
                store,
                requireComponents.useCases.tabsUseCases.selectTab,
                switchToTabDescription = getString(awesomebarR.string.switch_to_tab_description),
                resultsUriFilter = { it.toString() != StartPage.URL && it.toString() != "about:blank" },
            ),
        )

        AwesomeBarFeature(
                awesomeBar,
                toolbar,
                engineView,
                // What the feature does by itself, plus taking the start page away while the address is edited.
                onEditStart = {
                    engineView.asView().isVisible = false
                    awesomeBar.isVisible = true
                    startPageIntegration.get()?.editing = true
                },
                onEditComplete = {
                    awesomeBar.isVisible = false
                    engineView.asView().isVisible = true
                    startPageIntegration.get()?.editing = false
                },
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
        if (showTabsJob?.isActive == true) return
        val tab = requireComponents.core.store.state.selectedTab
        val thumbnailStorage = requireComponents.core.thumbnailStorage
        val lowOnMemory = requireContext().isOSOnLowMemory()
        showTabsJob =
            viewLifecycleOwner.lifecycleScope.launch {
                // A fresh picture of the tab being left: the one taken when its page loaded can be far out of date. It
                // is saved before the tray opens, or the tray would read the old one from disk. The tray shows its own
                // card for a start-page tab.
                if (tab != null && !StartPage.isStartTab(tab) && !lowOnMemory) {
                    withTimeoutOrNull(THUMBNAIL_TIMEOUT_MS) {
                        val bitmap = suspendCancellableCoroutine { continuation ->
                            engineView.captureThumbnail { if (continuation.isActive) continuation.resume(it) }
                        }
                        bitmap
                            ?.let { thumbnailStorage.saveThumbnail(ImageSaveRequest(tab.id, tab.content.private), it) }
                            ?.join()
                    }
                }
                // For now we are performing manual fragment transactions here. Once we can use the new
                // navigation support library we may want to pass navigation graphs around.
                activity
                    ?.supportFragmentManager
                    ?.takeUnless { it.isStateSaved }
                    ?.beginTransaction()
                    ?.apply {
                        replace(R.id.container, TabsTrayFragment())
                        commit()
                    }
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
                    val suggestions = provider.onInputChanged(text)
                    // The request cannot be interrupted, and the awesome bar shows whatever a provider returns, even
                    // after the text changed: answers for "a" arriving after those for "arch" replaced them. Typing
                    // cancels this call, so drop the answer then.
                    currentCoroutineContext().ensureActive()
                    suggestions.map { (it as? Suggestion)?.copy(provider = this) ?: it }
                } else {
                    emptyList()
                }
        }
    }

    override fun onBackPressed(): Boolean = readerViewFeature.onBackPressed() || super.onBackPressed()

    companion object {
        // A capture normally takes a few frames; never keep the user from the tray for long.
        private const val THUMBNAIL_TIMEOUT_MS = 500L

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
