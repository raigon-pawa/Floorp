/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.pip

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.mapNotNull
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.selector.findTabOrCustomTabOrSelectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.session.PictureInPictureFeature
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.log.logger.Logger

/**
 * Enters picture-in-picture when the user leaves the app while the page is fullscreen and its media is playing, or on
 * any page whose URL contains a [whiteList] entry.
 */
class PictureInPictureIntegration(
    private val store: BrowserStore,
    activity: Activity,
    private val customTabId: String?,
    private val whiteList: List<String> = listOf("youtube.com/tv"),
) : LifecycleAwareFeature {
    private var scope: CoroutineScope? = null
    // The tab id makes it judge the custom tab of this window rather than the selected browser tab.
    private val pictureFeature = PictureInPictureFeature(store, activity, tabId = customTabId)
    private var whiteListed = false

    override fun start() {
        scope =
            store.flowScoped(dispatcher = Dispatchers.Main) { flow ->
                flow
                    .mapNotNull { state -> state.findTabOrCustomTabOrSelectedTab(customTabId) }
                    .distinctUntilChangedBy { it.content.url }
                    .collect { whiteListed = isWhitelisted(it.content.url) }
            }
    }

    override fun stop() {
        scope?.cancel()
    }

    /**
     * Pages whose URL contains a [whiteList] entry enter PiP without the fullscreen-and-playing check of
     * [PictureInPictureFeature].
     */
    fun onHomePressed() =
        if (whiteListed) {
            try {
                pictureFeature.enterPipModeCompat()
            } catch (e: IllegalStateException) {
                // Thrown when the activity or device refuses, as PictureInPictureFeature.onHomePressed also expects.
                Logger.warn("Entering picture-in-picture failed", e)
                false
            }
        } else {
            pictureFeature.onHomePressed()
        }

    /**
     * Tells the engine and the store when the window enters or leaves picture-in-picture. Done here rather than by
     * [PictureInPictureFeature.onPictureInPictureModeChanged], which always tells the selected tab's engine session,
     * also in a custom tab.
     */
    fun onPictureInPictureModeChanged(enabled: Boolean) {
        val tab = store.state.findTabOrCustomTabOrSelectedTab(customTabId) ?: return
        tab.engineState.engineSession?.onPipModeChanged(enabled)
        store.dispatch(ContentAction.PictureInPictureChangedAction(tab.id, enabled))
    }

    private fun isWhitelisted(url: String): Boolean {
        val exists = whiteList.firstOrNull { url.contains(it) }
        return exists != null
    }
}
