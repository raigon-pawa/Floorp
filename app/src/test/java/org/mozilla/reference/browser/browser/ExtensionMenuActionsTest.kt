/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import mozilla.components.browser.state.state.WebExtensionState
import mozilla.components.browser.state.state.createTab
import mozilla.components.concept.engine.webextension.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Which extension actions the menu lists, and with which state. */
class ExtensionMenuActionsTest {
    private fun action(title: String?, enabled: Boolean? = true, badge: String? = null, onClick: () -> Unit = {}) =
        Action(title, enabled, null, badge, null, null, onClick)

    private val blocker = WebExtensionState("blocker", name = "Blocker", browserAction = action("Blocker"))
    private val reader =
        WebExtensionState("reader", name = "Reader", allowedInPrivateBrowsing = true, browserAction = action("Reader"))

    private fun titles(extensions: List<WebExtensionState>, private: Boolean = false) =
        extensionMenuActions(extensions, createTab("https://example.com/", private = private)).map { it.shown.title }

    @Test
    fun `enabled extensions are listed by name`() {
        assertEquals(listOf("Blocker", "Reader"), titles(listOf(reader, blocker)))
        assertEquals(listOf("Reader"), titles(listOf(reader, blocker.copy(enabled = false))))
    }

    @Test
    fun `a private tab lists only extensions allowed in private browsing`() {
        assertEquals(listOf("Reader"), titles(listOf(blocker, reader), private = true))
        assertEquals(emptyList<String>(), titles(listOf(blocker), private = true))
    }

    @Test
    fun `page actions are listed only while enabled`() {
        val page = WebExtensionState("page", name = "Page", pageAction = action("Page", enabled = false))
        assertEquals(emptyList<String>(), titles(listOf(page)))
        assertEquals(listOf("Page"), titles(listOf(page.copy(pageAction = action("Page", enabled = true)))))
    }

    @Test
    fun `the tab's overrides are shown and the global action is clicked`() {
        val globalClick = {}
        val extension = blocker.copy(browserAction = action("Blocker", onClick = globalClick))
        val override = WebExtensionState("blocker", browserAction = action("Blocker (26)", badge = "26"))
        val tab = createTab("https://example.com/", extensions = mapOf("blocker" to override))

        val listed = extensionMenuActions(listOf(extension), tab).single()
        assertEquals("Blocker (26)", listed.shown.title)
        assertEquals("26", listed.shown.badgeText)
        assertSame(globalClick, listed.onClick)
        assertEquals("Blocker", listed.name)
    }

    @Test
    fun `without a tab the global actions are listed`() {
        assertEquals(listOf("Blocker"), extensionMenuActions(listOf(blocker), null).map { it.shown.title })
    }

    @Test
    fun `rows are labelled with the extension's name, not the action's tooltip`() {
        val proxy = WebExtensionState("proxy", name = "FoxyProxy", browserAction = action("Disable"))
        assertEquals(
            listOf("Blocker", "FoxyProxy"),
            extensionMenuActions(listOf(proxy, blocker), null).map { it.label },
        )
        val unnamed = WebExtensionState("unnamed", name = " ", browserAction = action("Tooltip"))
        assertEquals("Tooltip", extensionMenuActions(listOf(unnamed), null).single().label)
    }
}
