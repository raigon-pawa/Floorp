/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.WebExtensionState
import mozilla.components.concept.engine.webextension.Action

/** One extension action as the menu lists it: [shown] carries the tab's overrides, [onClick] runs the action. */
data class ExtensionMenuAction(val name: String, val shown: Action, val onClick: () -> Unit)

/**
 * The extension actions the menu lists for [tab], sorted by extension name: browser actions, and page actions while
 * enabled, of enabled extensions, with the tab's overrides applied; in a private tab only extensions allowed there.
 */
fun extensionMenuActions(extensions: Collection<WebExtensionState>, tab: SessionState?): List<ExtensionMenuAction> =
    extensions
        .filter { it.enabled && (tab?.content?.private != true || it.allowedInPrivateBrowsing) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
        .flatMap { extension ->
            val overrides = tab?.extensionState?.get(extension.id)
            val browserAction = extension.browserAction?.let { it to it.copyWithOverride(overrides?.browserAction) }
            val pageAction =
                extension.pageAction
                    ?.let { it to it.copyWithOverride(overrides?.pageAction) }
                    ?.takeIf { (_, shown) -> shown.enabled == true }
            listOfNotNull(browserAction, pageAction).map { (global, shown) ->
                // The toolbar used to click the global action too; the engine applies it to the current tab.
                ExtensionMenuAction(extension.name.orEmpty(), shown, global.onClick)
            }
        }
