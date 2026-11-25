/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import mozilla.components.browser.domains.autocomplete.ShippedDomainsProvider
import mozilla.components.browser.menu2.BrowserMenuController
import mozilla.components.browser.state.selector.findCustomTab
import mozilla.components.browser.state.selector.findTabOrCustomTabOrSelectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.browser.toolbar.BrowserToolbar
import mozilla.components.browser.toolbar.display.DisplayToolbar
import mozilla.components.concept.menu.MenuController
import mozilla.components.concept.menu.candidate.CompoundMenuCandidate
import mozilla.components.concept.menu.candidate.ContainerStyle
import mozilla.components.concept.menu.candidate.DividerMenuCandidate
import mozilla.components.concept.menu.candidate.DrawableMenuIcon
import mozilla.components.concept.menu.candidate.MenuCandidate
import mozilla.components.concept.menu.candidate.NestedMenuCandidate
import mozilla.components.concept.menu.candidate.RowMenuCandidate
import mozilla.components.concept.menu.candidate.SmallMenuCandidate
import mozilla.components.concept.menu.candidate.TextMenuCandidate
import mozilla.components.feature.addons.menu.createMenuCandidate
import mozilla.components.feature.pwa.WebAppUseCases
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.feature.toolbar.ToolbarAutocompleteFeature
import mozilla.components.feature.toolbar.ToolbarFeature
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.ktx.android.view.ImeInsetsSynchronizer
import mozilla.components.support.ktx.kotlinx.coroutines.flow.ifAnyChanged
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.addons.AddonsActivity
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.ext.share
import org.mozilla.reference.browser.settings.SettingsActivity

private const val URL_TEXT_SIZE_SP = 13f

/**
 * Wires the browser toolbar to the selected tab, or to the tab given by [sessionId]: URL loading and search,
 * autocomplete, the overflow menu and keeping the toolbar above the keyboard.
 */
@Suppress("LongParameterList")
class ToolbarIntegration(
    private val context: Context,
    toolbar: BrowserToolbar,
    toolbarParentView: View,
    historyStorage: PlacesHistoryStorage,
    private val store: BrowserStore,
    private val sessionUseCases: SessionUseCases,
    private val tabsUseCases: TabsUseCases,
    private val webAppUseCases: WebAppUseCases,
    private val sessionId: String? = null,
) : LifecycleAwareFeature, UserInteractionHandler {
    private val shippedDomainsProvider =
        ShippedDomainsProvider().also {
            it.initialize(context)
        }

    private val privatePageUrl = UrlDisplay.privatePageUrl(context)

    private val scope = MainScope()
    private var menuUpdateJob: Job? = null

    private fun rowButton(
        @StringRes description: Int,
        @DrawableRes icon: Int,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) =
        SmallMenuCandidate(
            contentDescription = context.getString(description),
            icon =
                DrawableMenuIcon(
                    context,
                    icon,
                    // The menu does not dim a disabled button by itself.
                    tint = ContextCompat.getColor(context, if (enabled) R.color.icons else R.color.aw_text_disabled),
                ),
            containerStyle = ContainerStyle(isEnabled = enabled),
            onClick = onClick,
        )

    private fun menuToolbar(tab: SessionState): RowMenuCandidate {
        val content = tab.content
        return RowMenuCandidate(
            listOf(
                rowButton(R.string.menu_back, iconsR.drawable.mozac_ic_back_24, content.canGoBack) {
                    sessionUseCases.goBack.invoke(tab.id)
                },
                rowButton(R.string.menu_forward, iconsR.drawable.mozac_ic_forward_24, content.canGoForward) {
                    sessionUseCases.goForward.invoke(tab.id)
                },
                // One slot that follows the loading state.
                if (content.loading) {
                    rowButton(R.string.menu_stop, iconsR.drawable.mozac_ic_cross_24) {
                        sessionUseCases.stopLoading.invoke(tab.id)
                    }
                } else {
                    rowButton(R.string.menu_reload, iconsR.drawable.mozac_ic_arrow_clockwise_24) {
                        sessionUseCases.reload.invoke(tab.id)
                    }
                },
                rowButton(
                    R.string.menu_share,
                    iconsR.drawable.mozac_ic_share_android_24,
                    // A blank page has nothing to share, and our private page's data: URL is not worth sharing.
                    !UrlDisplay.isHidden(content.url, privatePageUrl),
                ) {
                    context.share(content.url)
                },
            )
        )
    }

    private fun pageMenuItems(tab: SessionState): List<MenuCandidate> =
        listOfNotNull(
            TextMenuCandidate(context.getString(R.string.menu_find_in_page)) {
                FindInPageIntegration.launch?.invoke()
            },
            CompoundMenuCandidate(
                text = context.getString(R.string.menu_desktop_site),
                isChecked = tab.content.desktopMode,
                end = CompoundMenuCandidate.ButtonType.SWITCH,
            ) { checked ->
                sessionUseCases.requestDesktopSite.invoke(checked, tab.id)
            },
            // Only a web page can be pinned, and only where the launcher supports it.
            if (webAppUseCases.isPinningSupported() && tab.content.url.startsWith("http")) {
                TextMenuCandidate(context.getString(R.string.menu_add_to_home_screen)) {
                    scope.launch { webAppUseCases.addToHomescreen() }
                }
            } else {
                null
            },
            DividerMenuCandidate(),
        )

    private fun menuItems(state: BrowserState): List<MenuCandidate> {
        val tab = state.findTabOrCustomTabOrSelectedTab(sessionId)
        return listOfNotNull(tab?.let(::menuToolbar)) +
            listOf(
                TextMenuCandidate(context.getString(R.string.menu_new_tab)) {
                    tabsUseCases.addTab(url = "about:blank", selectTab = true)
                },
                TextMenuCandidate(context.getString(R.string.menu_new_private_tab)) {
                    tabsUseCases.addTab(url = "about:privatebrowsing", selectTab = true, private = true)
                },
                DividerMenuCandidate(),
                extensionsMenuItem(state, tab),
                DividerMenuCandidate(),
            ) +
            (tab?.let(::pageMenuItems) ?: emptyList()) +
            TextMenuCandidate(context.getString(R.string.menu_settings)) {
                startActivity(SettingsActivity::class.java)
            }
    }

    /**
     * One row per extension action (browser actions, and page actions while enabled) with its icon, badge and disabled
     * state, tab overrides applied and, in a private tab, only extensions allowed there; then "Manage extensions".
     * Without any action the entry opens the add-ons manager directly.
     */
    private fun extensionsMenuItem(state: BrowserState, tab: SessionState?): MenuCandidate {
        val title = context.getString(R.string.menu_extensions)
        val actions =
            state.extensions.values
                .filter { it.enabled && (tab?.content?.private != true || it.allowedInPrivateBrowsing) }
                .sortedBy { it.name }
                .flatMap { extension ->
                    val overrides = tab?.extensionState?.get(extension.id)
                    val browserAction =
                        extension.browserAction?.let { it to it.copyWithOverride(overrides?.browserAction) }
                    val pageAction =
                        extension.pageAction
                            ?.let { it to it.copyWithOverride(overrides?.pageAction) }
                            ?.takeIf { (_, shown) -> shown.enabled == true }
                    listOfNotNull(browserAction, pageAction).map { (global, shown) ->
                        // The toolbar used to click the global action too; the engine applies it to the current tab.
                        shown.createMenuCandidate(context, onClick = global.onClick).let {
                            if (it.text.isEmpty()) it.copy(text = extension.name.orEmpty()) else it
                        }
                    }
                }
        if (actions.isEmpty()) {
            return TextMenuCandidate(title) { startActivity(AddonsActivity::class.java) }
        }

        val tint = ContextCompat.getColor(context, R.color.icons)
        val back =
            NestedMenuCandidate(
                id = R.string.menu_back,
                text = title,
                start = DrawableMenuIcon(context, iconsR.drawable.mozac_ic_back_24, tint = tint),
                subMenuItems = null,
            )
        val manage =
            TextMenuCandidate(
                context.getString(R.string.menu_manage_extensions),
                start = DrawableMenuIcon(context, iconsR.drawable.mozac_ic_extension_24, tint = tint),
            ) {
                startActivity(AddonsActivity::class.java)
            }
        return NestedMenuCandidate(
            id = R.string.menu_extensions,
            text = title,
            subMenuItems = listOf(back, DividerMenuCandidate()) + actions + listOf(DividerMenuCandidate(), manage),
        )
    }

    private fun startActivity(activity: Class<*>) {
        context.startActivity(Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private val browserMenuController: MenuController = BrowserMenuController()

    init {
        toolbar.display.apply {
            indicators =
                listOf(
                    DisplayToolbar.Indicators.SECURITY,
                    DisplayToolbar.Indicators.TRACKING_PROTECTION,
                )
            displayIndicatorSeparator = true
            menuController = browserMenuController
            hint = context.getString(R.string.toolbar_hint)

            setUrlBackground(ResourcesCompat.getDrawable(context.resources, R.drawable.url_background, context.theme))

            urlFormatter = { url -> UrlDisplay.format(url.toString(), privatePageUrl) }
            // A blank page or our private page has nothing worth editing: open an empty field, not its address.
            onUrlClicked = {
                if (UrlDisplay.isHidden(toolbar.url.toString(), privatePageUrl)) {
                    toolbar.editMode()
                    toolbar.edit.updateUrl("")
                    false
                } else {
                    true
                }
            }

            val white = ContextCompat.getColor(context, R.color.aw_text_1)
            colors =
                colors.copy(
                    hint = ContextCompat.getColor(context, R.color.aw_text_hint),
                    title = white,
                    text = white,
                    trackingProtection = white,
                    separator = ContextCompat.getColor(context, R.color.aw_divider),
                    highlight = white,
                )
        }

        toolbar.edit.apply {
            hint = context.getString(R.string.toolbar_hint)
            setUrlBackground(ResourcesCompat.getDrawable(context.resources, R.drawable.url_background, context.theme))

            // The library's default highlight for the inline autocomplete suggestion is magenta.
            colors =
                colors.copy(
                    hint = ContextCompat.getColor(context, R.color.aw_text_hint),
                    text = ContextCompat.getColor(context, R.color.aw_text_1),
                    suggestionBackground = ContextCompat.getColor(context, R.color.aw_selection),
                    suggestionForeground = ContextCompat.getColor(context, R.color.aw_text_1),
                )
        }

        // JetBrains Mono for the address only (shown and edited). It is wider than the system font.
        val mono = ResourcesCompat.getFont(context, R.font.jetbrains_mono_nl_regular)
        toolbar.display.typeface = mono
        toolbar.edit.typeface = mono
        // A custom tab keeps the library's sizes: it shows a title above the URL, and the library resets the URL's
        // size whenever the title changes.
        if (sessionId == null || store.state.findCustomTab(sessionId) == null) {
            toolbar.display.textSize = URL_TEXT_SIZE_SP
            toolbar.edit.textSize = URL_TEXT_SIZE_SP
        }

        ToolbarAutocompleteFeature(toolbar).apply {
            updateAutocompleteProviders(listOf(historyStorage, shippedDomainsProvider))
        }

        ImeInsetsSynchronizer.setup(
            targetView = toolbar,
            onIMEAnimationStarted = { isKeyboardShowingUp, keyboardHeight ->
                // If the keyboard is hiding have the engine view immediately expand to the entire height of the
                // screen and ensure the toolbar is shown above keyboard before both would be animated down.
                if (!isKeyboardShowingUp) {
                    (toolbarParentView.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
                    (toolbar.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = keyboardHeight
                    toolbarParentView.requestLayout()
                }
            },
            onIMEAnimationFinished = { isKeyboardShowingUp, keyboardHeight ->
                // If the keyboard is showing up keep the engine view covering the entire height
                // of the screen until the animation is finished to avoid reflowing the web content
                // together with the keyboard animation in a short burst of updates.
                if (isKeyboardShowingUp || keyboardHeight == 0) {
                    (toolbarParentView.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = keyboardHeight
                    (toolbar.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
                    toolbarParentView.requestLayout()
                }
            },
        )
    }

    private val toolbarFeature: ToolbarFeature =
        ToolbarFeature(
            toolbar,
            context.components.core.store,
            context.components.useCases.sessionUseCases.loadUrl,
            { searchTerms ->
                context.components.useCases.searchUseCases.defaultSearch.invoke(
                    searchTerms = searchTerms,
                    searchEngine = null,
                    parentSessionId = null,
                )
            },
            sessionId,
        )

    override fun start() {
        toolbarFeature.start()

        menuUpdateJob?.cancel()
        menuUpdateJob = scope.launch {
            // Only what the menu shows, so progress and title updates do not rebuild it.
            store
                .flow()
                .ifAnyChanged { state ->
                    val tab = state.findTabOrCustomTabOrSelectedTab(sessionId)
                    arrayOf(
                        tab?.id,
                        tab?.content?.url,
                        tab?.content?.private,
                        tab?.content?.loading,
                        tab?.content?.canGoBack,
                        tab?.content?.canGoForward,
                        tab?.content?.desktopMode,
                        tab?.extensionState,
                        state.extensions,
                    )
                }
                .collect { state ->
                    browserMenuController.submitList(menuItems(state))
                }
        }
    }

    override fun stop() {
        toolbarFeature.stop()

        menuUpdateJob?.cancel()
        menuUpdateJob = null
    }

    override fun onBackPressed(): Boolean = toolbarFeature.onBackPressed()
}
