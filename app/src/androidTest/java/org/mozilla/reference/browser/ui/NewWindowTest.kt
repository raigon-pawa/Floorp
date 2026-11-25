/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ui

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiSelector
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.helpers.AndroidAssetDispatcher
import org.mozilla.reference.browser.helpers.BrowserActivityTestRule
import org.mozilla.reference.browser.helpers.RetryTestRule
import org.mozilla.reference.browser.helpers.TestAssetHelper.waitingTime
import org.mozilla.reference.browser.helpers.TestHelper
import org.mozilla.reference.browser.helpers.ext.toUri
import org.mozilla.reference.browser.ui.robots.browser
import org.mozilla.reference.browser.ui.robots.mDevice
import org.mozilla.reference.browser.ui.robots.navigationToolbar

/** A page that opens a window gets exactly one new tab for it. */
class NewWindowTest {
    private lateinit var mockWebServer: MockWebServer

    @get:Rule val activityTestRule = BrowserActivityTestRule()

    @Rule @JvmField val retryTestRule = RetryTestRule(3)

    @Before
    fun setUp() {
        mockWebServer =
            MockWebServer().apply {
                dispatcher = AndroidAssetDispatcher()
                start()
            }
    }

    @After
    fun tearDown() {
        runCatching { mockWebServer.close() }
    }

    @Test
    fun targetBlankLinkOpensOneTabTest() {
        val page = mockWebServer.url("pages/new_window.html").toString().toUri()!!

        navigationToolbar {}.enterUrlAndEnterToBrowser(page) { verifyPageContent("Page content: new window") }
        val tabsBefore = tabCount()

        val link = mDevice.findObject(UiSelector().textContains("Open in a new window"))
        link.waitForExists(waitingTime)
        link.click()
        browser { verifyPageContent("Page content: 1") }
        // Both copies of the window feature used to answer the same request, a moment apart.
        SystemClock.sleep(WINDOW_SETTLE_MS)

        assertEquals(tabsBefore + 1, tabCount())
    }

    private fun tabCount(): Int {
        var count = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            count = TestHelper.appContext.components.core.store.state.tabs.size
        }
        return count
    }

    private companion object {
        const val WINDOW_SETTLE_MS = 2000L
    }
}
