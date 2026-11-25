/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The licence pages are read from the assets by name: a renamed or missing file would crash when its row is tapped. */
class LicensesTest {
    @Test
    fun `every licence page has its asset`() {
        val paths = LicensesFragment.assetPaths
        assertTrue(paths.isNotEmpty())
        paths.forEach { assertTrue("missing asset $it", File("src/main/assets", it).isFile) }
    }
}
