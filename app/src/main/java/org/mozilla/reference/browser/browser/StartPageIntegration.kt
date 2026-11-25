/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.toolbar.BrowserToolbar
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.feature.LifecycleAwareFeature

/**
 * Shows the start page in [view] whenever [StartPage.isShown] says so, except while the address is edited. It sits
 * above the engine view and below the awesome bar. A tile loads its site in the current tab (or a new one when there is
 * none), so Back returns to the start page.
 */
class StartPageIntegration(
    private val view: ComposeView,
    private val engineView: View,
    private val store: BrowserStore,
    private val toolbar: BrowserToolbar,
    private val loadUrl: SessionUseCases.LoadUrlUseCase,
) : LifecycleAwareFeature {
    private var scope: CoroutineScope? = null
    private var shown = StartPage.isShown(store.state)

    /**
     * True while the address is edited. The awesome bar then takes the screen; the page is hidden rather than only
     * covered, so TalkBack does not find it behind the suggestions.
     */
    var editing = false
        set(value) {
            field = value
            render()
        }

    init {
        // The fragment's view is rebuilt on every tabs tray visit; the composition goes with it.
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        view.setContent { StartPageContent(onSearch = ::editAddress, onOpen = { loadUrl(it) }) }
        // Right away, so coming back from the tabs tray does not show a frame of the blank page first.
        render()
    }

    override fun start() {
        scope =
            store.flowScoped(dispatcher = Dispatchers.Main) { flow ->
                flow.map(StartPage::isShown).distinctUntilChanged().collect {
                    shown = it
                    // The start page cannot scroll the toolbar back in, so never leave it scrolled away by a page.
                    if (it) toolbar.expand()
                    render()
                }
            }
    }

    override fun stop() {
        scope?.cancel()
    }

    private fun render() {
        view.isVisible = shown && !editing
        // TalkBack follows the view tree, not what covers what: keep it out of the empty page underneath.
        engineView.importantForAccessibility =
            if (view.isVisible) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    // The address bar would open with the start page's address in it.
    private fun editAddress() {
        toolbar.editMode()
        toolbar.edit.updateUrl("")
    }
}
