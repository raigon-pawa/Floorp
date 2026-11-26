/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ui

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.helpers.AndroidAssetDispatcher
import org.mozilla.reference.browser.helpers.BrowserActivityTestRule
import org.mozilla.reference.browser.helpers.TestAssetHelper
import org.mozilla.reference.browser.helpers.TestHelper
import org.mozilla.reference.browser.helpers.TestHelper.packageName
import org.mozilla.reference.browser.ui.robots.browser
import org.mozilla.reference.browser.ui.robots.mDevice
import org.mozilla.reference.browser.ui.robots.navigationToolbar
import org.mozilla.reference.browser.ui.robots.startPage

/** The start page: what a new install shows and where its tiles and Back lead. */
class StartPageTest {
    private lateinit var mockWebServer: MockWebServer

    // No RetryTestRule: a retry starts on the page the failed attempt left, so it fails again and hides the cause.
    @get:Rule val activityTestRule = BrowserActivityTestRule()

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

    // Every test starts from cleared app data, so this is a first launch.
    @Test
    fun firstLaunchShowsStartPageTest() {
        startPage { verifyStartPage() }
        navigationToolbar {
            verifyNoTabAddressView()
            checkNumberOfTabsTabCounter("1")
        }
    }

    // Needs the network: the tile's site is real.
    @Test
    fun tileOpensSiteTest() {
        TestHelper.assumeValidatedNetwork("the DuckDuckGo tile needs the network")
        startPage {}
            .clickTile("DuckDuckGo") {
                verifyUrl("duckduckgo.com")
            }
            .goBack {}
        startPage { verifyStartPage() }
    }

    @Test
    fun backFromFirstPageReturnsToStartPageTest() {
        val page = TestAssetHelper.getGenericAsset(mockWebServer, 1)

        navigationToolbar {}.enterUrlAndEnterToBrowser(page.url) { verifyPageContent(page.content) }
        startPage { verifyNoStartPage() }
        browser {}.goBack {}
        startPage { verifyStartPage() }
        navigationToolbar { checkNumberOfTabsTabCounter("1") }
    }

    // Decision 11: Back with nothing to go back to leaves the app like Home, keeping the tab, and never closes it.
    @Test
    fun backOnStartPageLeavesTheAppTest() {
        startPage { verifyStartPage() }
        val store = InstrumentationRegistry.getInstrumentation().targetContext.components.core.store
        val startTab = store.state.selectedTabId
        mDevice.pressBack()
        assertTrue("still on screen", mDevice.wait(Until.gone(By.pkg(packageName)), TestAssetHelper.waitingTime))
        assertFalse("the browser was closed", activityTestRule.activity.isFinishing)
        // The same tab, not a fresh start tab opened after it was closed.
        assertEquals(listOf(startTab), store.state.tabs.map { it.id })
        assertEquals(startTab, store.state.selectedTabId)
    }

    // Closing the last tab: TabTrayMenuTest.closeOneTabXButtonTest.
}
