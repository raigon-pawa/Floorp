/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.addons

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionalPermissionsPromptTest {
    @Test
    fun `nothing to show is granted without a prompt`() {
        assertTrue(canGrantOptionalPermissionsWithoutPrompt(emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun `site access alone needs a prompt`() {
        assertFalse(canGrantOptionalPermissionsWithoutPrompt(emptyList(), listOf("<all_urls>"), emptyList()))
        assertFalse(canGrantOptionalPermissionsWithoutPrompt(emptyList(), listOf("*://*.example.net/*"), emptyList()))
    }

    @Test
    fun `data collection alone needs a prompt`() {
        assertFalse(
            canGrantOptionalPermissionsWithoutPrompt(emptyList(), emptyList(), listOf("technicalAndInteraction"))
        )
    }

    @Test
    fun `an API permission with a message needs a prompt`() {
        assertFalse(canGrantOptionalPermissionsWithoutPrompt(listOf("Access browser tabs"), emptyList(), emptyList()))
    }
}
