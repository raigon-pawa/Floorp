/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ui

import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mozilla.reference.browser.helpers.AndroidAssetDispatcher
import org.mozilla.reference.browser.helpers.BrowserActivityTestRule
import org.mozilla.reference.browser.helpers.RetryTestRule
import org.mozilla.reference.browser.helpers.TestAssetHelper
import org.mozilla.reference.browser.ui.robots.browser
import org.mozilla.reference.browser.ui.robots.navigationToolbar
import org.mozilla.reference.browser.ui.robots.startPage

/** The start page: what a new install shows and where its tiles and Back lead. */
class StartPageTest {
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

    // Closing the last tab: TabTrayMenuTest.closeOneTabXButtonTest.
}
