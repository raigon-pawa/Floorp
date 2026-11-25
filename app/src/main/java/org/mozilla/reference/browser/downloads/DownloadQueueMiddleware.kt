/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.downloads

import android.os.Handler
import android.os.Looper
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.CustomTabListAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.findTabOrCustomTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

/**
 * A tab holds one download request at a time, and a page that starts several downloads at once replaced the first
 * with each next one: one dialog, one file, the rest gone without a word. Queue the requests that arrive while one is
 * waiting for the user and offer the next once it is answered. A page left for another one drops its queue.
 */
class DownloadQueueMiddleware(
    // shortcut: the answered dialog is still being dismissed, and the downloads feature shows no dialog while one is
    // there, so the next request waits a moment; tie it to the dialog's removal if the delay ever proves too short.
    private val later: (() -> Unit) -> Unit = { Handler(Looper.getMainLooper()).postDelayed(it, NEXT_DIALOG_DELAY_MS) },
) : Middleware<BrowserState, BrowserAction> {
    // Only touched on the store's thread.
    private val queues = mutableMapOf<String, ArrayDeque<DownloadState>>()

    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        when (action) {
            is ContentAction.UpdateDownloadAction -> {
                val waiting = store.state.findTabOrCustomTab(action.sessionId)?.content?.download
                if (waiting != null && waiting.id != action.download.id) {
                    queues.getOrPut(action.sessionId) { ArrayDeque() }.add(action.download)
                    return
                }
            }
            is ContentAction.ConsumeDownloadAction,
            is ContentAction.CancelDownloadAction -> {
                next(action)
                val sessionId =
                    if (action is ContentAction.ConsumeDownloadAction) action.sessionId
                    else (action as ContentAction.CancelDownloadAction).sessionId
                queues[sessionId]?.removeFirstOrNull()?.let {
                    later { store.dispatch(ContentAction.UpdateDownloadAction(sessionId, it)) }
                }
                return
            }
            is ContentAction.UpdateUrlAction -> drop(action.sessionId)
            is TabListAction,
            is CustomTabListAction -> {
                next(action)
                queues.keys.filter { store.state.findTabOrCustomTab(it) == null }.forEach(::drop)
                return
            }
            else -> Unit
        }
        next(action)
    }

    private fun drop(sessionId: String) {
        queues.remove(sessionId)?.forEach { it.response?.close() }
    }

    private companion object {
        const val NEXT_DIALOG_DELAY_MS = 500L
    }
}
