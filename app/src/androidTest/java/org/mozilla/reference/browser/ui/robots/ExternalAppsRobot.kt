/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ui.robots

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiSelector
import org.junit.Assert.assertTrue
import org.mozilla.reference.browser.helpers.TestAssetHelper.waitingTime

/** Implementation of Robot Pattern for any non-Reference Browser (external) apps. */
class ExternalAppsRobot {
    fun verifyAndroidDefaultApps() = assertDefaultAppsLayout()

    fun verifyYouTubeApp() = assertYouTubeApp()

    class Transition {
        fun externalApps(): ExternalAppsRobot.Transition = ExternalAppsRobot.Transition()
    }
}

private fun assertDefaultAppsLayout() {
    // The title is the collapsing toolbar's description on Android 16, plain text on older versions. A cold Settings
    // app
    // can take more than a few seconds to open on the emulator, so wait the full time for either.
    fun shown() = mDevice.hasObject(By.desc("Default apps")) || mDevice.hasObject(By.text("Default apps"))
    val deadline = SystemClock.uptimeMillis() + waitingTime
    while (!shown() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(POLL_MS)
    assertTrue("Default apps did not open", shown())
}

private const val POLL_MS = 250L

private fun assertYouTubeApp() {
    try {
        // Check youtube's home buttons
        mDevice.waitForIdle()
        assertTrue(mDevice.findObject(UiSelector().text("Home")).waitForExists(waitingTime))
        assertTrue(mDevice.findObject(UiSelector().text("Subscriptions")).waitForExists(waitingTime))
    } catch (e: AssertionError) {
        println("The native youtube app opens but needs to be updated")
        // In case the app isn't up to date on the emulator an update message will be displayed
        assertTrue(
            mDevice.findObject(UiSelector().text("Update for a faster, better YouTube")).waitForExists(waitingTime)
        )
    }
}
