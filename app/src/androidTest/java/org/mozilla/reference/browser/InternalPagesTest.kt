/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The error pages are the library's HTML and script with the app's stylesheet, which replaces the library's only
 * because it has the same asset name. A library update that renames the stylesheet would bring the old look back
 * without failing the build, so this test checks what was packaged.
 */
class InternalPagesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun asset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }

    @Test
    fun errorPagesUseTheAppStylesheet() {
        assertTrue(asset("error_page_js.html").contains("href=\"error_style.css\""))

        val css = asset("error_style.css")
        assertTrue("the library's stylesheet was packaged", css.contains("Archwave"))
        // The page script shows these by setting style.display; they must start hidden.
        for (id in listOf("advancedButton", "badCertAdvancedPanel")) {
            assertTrue(id, Regex("#$id\\s*\\{\\s*display: none").containsMatchIn(css))
        }
    }

}
