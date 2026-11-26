/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.addons

import mozilla.components.feature.addons.Addon
import org.junit.Assert.assertEquals
import org.junit.Test

/** The add-on manager lists installed add-ons by name, like the menu; recommended ones stay in catalogue order. */
class InstalledByNameTest {
    private fun addon(id: String, installed: Boolean) =
        Addon(id, installedState = if (installed) Addon.InstalledState(id, "1.0", null) else null)

    @Test
    fun `installed add-ons are sorted by name ignoring case, the others keep their order`() {
        val addons =
            listOf(
                addon("uBlock Origin", installed = true),
                addon("Zeta", installed = false),
                addon("Dark Reader", installed = true),
                addon("Alpha", installed = false),
                addon("bitwarden", installed = true),
            )

        assertEquals(
            listOf("bitwarden", "Dark Reader", "uBlock Origin", "Zeta", "Alpha"),
            addons.installedByName { it.id }.map { it.id },
        )
    }
}
