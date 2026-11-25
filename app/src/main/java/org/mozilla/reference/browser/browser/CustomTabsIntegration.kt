/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import mozilla.components.browser.menu2.BrowserMenuController
import mozilla.components.browser.state.selector.findCustomTab
import mozilla.components.browser.state.state.CustomTabSessionState
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.toolbar.BrowserToolbar
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.menu.MenuController
import mozilla.components.concept.menu.candidate.CompoundMenuCandidate
import mozilla.components.concept.menu.candidate.ContainerStyle
import mozilla.components.concept.menu.candidate.DrawableMenuIcon
import mozilla.components.concept.menu.candidate.MenuCandidate
import mozilla.components.concept.menu.candidate.RowMenuCandidate
import mozilla.components.concept.menu.candidate.SmallMenuCandidate
import mozilla.components.concept.menu.candidate.TextMenuCandidate
import mozilla.components.feature.customtabs.CustomTabsColorsConfig
import mozilla.components.feature.customtabs.CustomTabsToolbarFeature
import mozilla.components.feature.customtabs.menu.createCustomTabMenuCandidates
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.CustomTabsUseCases
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.reference.browser.BrowserActivity
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.ext.share

/**
 * Toolbar and menu for a custom tab session, including the "Open in Browser" action that migrates the session into a
 * regular tab.
 */
@Suppress("LongParameterList")
class CustomTabsIntegration(
    private val context: Context,
    private val store: BrowserStore,
    toolbar: BrowserToolbar,
    private val engineView: EngineView,
    private val sessionUseCases: SessionUseCases,
    private val customTabsUseCases: CustomTabsUseCases,
    private val sessionId: String,
    private val activity: Activity?,
) : LifecycleAwareFeature, UserInteractionHandler {
    private val session = store.state.findCustomTab(sessionId)
    private val logger = Logger("CustomTabsIntegration")

    init {
        if (session == null) {
            logger.warn("The session for this ID, no longer exists. Finishing activity.")
            closeActivity()
        }
        toolbar.display.setUrlBackground(null)
    }

    private fun menuToolbar(session: SessionState?): RowMenuCandidate {
        val tint = ContextCompat.getColor(context, R.color.icons)
        val tabId = session?.id
        val canGoForward = session?.content?.canGoForward == true

        val forward =
            SmallMenuCandidate(
                contentDescription = context.getString(R.string.menu_forward),
                icon =
                    DrawableMenuIcon(
                        context,
                        mozilla.components.ui.icons.R.drawable.mozac_ic_forward_24,
                        // The menu does not dim a disabled button by itself.
                        tint = if (canGoForward) tint else ContextCompat.getColor(context, R.color.aw_text_disabled),
                    ),
                containerStyle = ContainerStyle(isEnabled = canGoForward),
            ) {
                sessionUseCases.goForward.invoke(tabId)
            }

        val refresh =
            SmallMenuCandidate(
                contentDescription = context.getString(R.string.menu_reload),
                icon =
                    DrawableMenuIcon(
                        context,
                        mozilla.components.ui.icons.R.drawable.mozac_ic_arrow_clockwise_24,
                        tint = tint,
                    ),
            ) {
                sessionUseCases.reload.invoke(tabId)
            }

        val stop =
            SmallMenuCandidate(
                contentDescription = context.getString(R.string.menu_stop),
                icon =
                    DrawableMenuIcon(
                        context,
                        mozilla.components.ui.icons.R.drawable.mozac_ic_cross_24,
                        tint = tint,
                    ),
            ) {
                sessionUseCases.stopLoading.invoke(tabId)
            }

        return RowMenuCandidate(listOf(forward, refresh, stop))
    }

    private fun menuItems(sessionState: SessionState?): List<MenuCandidate> =
        listOf(
            menuToolbar(sessionState),
            TextMenuCandidate(context.getString(R.string.menu_share)) {
                val url = sessionState?.content?.url.orEmpty()
                context.share(url)
            },
            CompoundMenuCandidate(
                text = context.getString(R.string.menu_desktop_site),
                isChecked = sessionState?.content?.desktopMode == true,
                end = CompoundMenuCandidate.ButtonType.SWITCH,
            ) { checked ->
                sessionUseCases.requestDesktopSite.invoke(checked, sessionState?.id)
            },
            TextMenuCandidate(context.getString(R.string.menu_find_in_page)) {
                FindInPageIntegration.launch?.invoke()
            },
            TextMenuCandidate(context.getString(R.string.archwave_menu_open_in_app)) {
                // Release the session from this view so that it can immediately be rendered by a different view
                engineView.release()

                // Stip the CustomTabConfig to turn this Session into a regular tab and then select it
                (sessionState as? CustomTabSessionState)?.let { session ->
                    customTabsUseCases.migrate(session.id)
                }

                // Close this activity since it is no longer displaying any session
                closeActivity()

                // Now switch to the actual browser which should now display our new selected session
                val intent = Intent(context, BrowserActivity::class.java)
                context.startActivity(intent)
            },
        )

    private val menuController: MenuController = BrowserMenuController()

    private val feature =
        CustomTabsToolbarFeature(
            store,
            toolbar,
            sessionId,
            customTabsUseCases,
            window = activity?.window,
            // Keep the app's own colours: ignore the toolbar and system bar colours the calling app asks for.
            // The caller's action button keeps its own colours, like a site icon: forceActionButtonTinting would
            // flatten a full-colour bitmap into a white silhouette, often a plain square.
            customTabsColorsConfig =
                CustomTabsColorsConfig(
                    updateStatusBarColor = false,
                    updateSystemNavigationBarColor = false,
                    updateToolbarsColor = false,
                ),
            closeListener = { closeActivity() },
        )

    // Opened in a task of its own (a link from an app that starts a new task), a closed custom tab left a card in
    // Recents that opened nothing. In the calling app's task, only this activity goes.
    private fun closeActivity() {
        activity?.run { if (isTaskRoot) finishAndRemoveTask() else finish() }
    }

    // Cancelled in stop(): left running, the store kept this integration and its activity alive after the tab closed.
    private var menuScope: CoroutineScope? = null

    init {
        toolbar.display.menuController = menuController
    }

    override fun start() {
        feature.start()
        menuScope =
            store.flowScoped(dispatcher = Dispatchers.Main) { flow ->
                flow
                    .map { state -> state.findCustomTab(sessionId) }
                    .distinctUntilChanged()
                    .collect { tab ->
                        val items = menuItems(tab)
                        val customTabItems = tab?.createCustomTabMenuCandidates(context).orEmpty()
                        menuController.submitList(items + customTabItems)
                    }
            }
    }

    override fun stop() {
        feature.stop()
        menuScope?.cancel()
        menuScope = null
    }

    override fun onBackPressed(): Boolean = feature.onBackPressed()
}
