/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ui.robots

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.mozilla.reference.browser.helpers.TestAssetHelper.waitingTime
import org.mozilla.reference.browser.helpers.TestHelper.packageName

/** Implementation of Robot Pattern for the start page. */
class StartPageRobot {
    fun verifyStartPage() {
        assertTrue("start page", mDevice.wait(Until.hasObject(By.res("$packageName:id/startPage")), waitingTime))
        TILES.forEach { assertTrue(it, mDevice.wait(Until.hasObject(By.text(it)), waitingTime)) }
    }

    fun verifyNoStartPage() =
        assertTrue("start page gone", mDevice.wait(Until.gone(By.res("$packageName:id/startPage")), waitingTime))

    class Transition {
        fun clickTile(
            name: String,
            interact: BrowserRobot.() -> Unit,
        ): BrowserRobot.Transition {
            val tile = mDevice.wait(Until.findObject(By.text(name)), waitingTime)
            assertNotNull("No start page tile '$name'", tile)
            tile.click()
            BrowserRobot().interact()
            return BrowserRobot.Transition()
        }
    }

    companion object {
        val TILES = listOf("ArchWiki", "Wikipedia", "GitHub", "DuckDuckGo")
    }
}

fun startPage(interact: StartPageRobot.() -> Unit): StartPageRobot.Transition {
    StartPageRobot().interact()
    return StartPageRobot.Transition()
}
