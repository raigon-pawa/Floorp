/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ui

import androidx.test.platform.app.InstrumentationRegistry
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.helpers.AndroidAssetDispatcher
import org.mozilla.reference.browser.helpers.BrowserActivityTestRule
import org.mozilla.reference.browser.helpers.RetryTestRule
import org.mozilla.reference.browser.helpers.TestAssetHelper
import org.mozilla.reference.browser.helpers.TestHelper
import org.mozilla.reference.browser.ui.robots.mDevice
import org.mozilla.reference.browser.ui.robots.navigationToolbar
import org.mozilla.reference.browser.ui.robots.startPage

/**
 * Tests for verifying tab tray menu:
 * - Appears when counter tabs is clicked
 * - Expected options are displayed as listed below
 * - Options/Buttons in this menu work as expected
 */
class TabTrayMenuTest {
    private lateinit var mockWebServer: MockWebServer

    @get:Rule val activityTestRule = BrowserActivityTestRule()

    @Rule @JvmField val retryTestRule = RetryTestRule(3)

    // A retry runs in the same process: start each attempt from a single start-page tab.
    @Before
    fun setUp() {
        mockWebServer =
            MockWebServer().apply {
                dispatcher = AndroidAssetDispatcher()
                start()
            }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            TestHelper.appContext.components.useCases.tabsUseCases.removeAllTabs()
        }
        mDevice.waitForIdle()
    }

    @After
    fun tearDown() {
        runCatching { mockWebServer.close() }
    }

    // This test verifies the tab tray menu items are all in place
    @Test
    fun tabTrayUITest() {
        navigationToolbar {}
            .openTabTrayMenu {
                verifyRegularBrowsingTab()
                verifyPrivateBrowsingTab()
                verifyGoBackButton()
                verifyNewTabButton()
            }
            .openMoreOptionsMenu(activityTestRule.activity) {
                verifyCloseAllTabsButton()
            }
    }

    // Close all tabs leaves one start-page tab
    @Test
    fun closeAllTabsTest() {
        val genericURL = TestAssetHelper.getGenericAsset(mockWebServer, 1)

        navigationToolbar {}.enterUrlAndEnterToBrowser(genericURL.url) { verifyPageContent(genericURL.content) }
        navigationToolbar {}
            .openTabTrayMenu {}
            .openNewTab {
                checkNumberOfTabsTabCounter("2")
            }
            .openTabTrayMenu {
                verifyExistingOpenTabs(genericURL.title)
                verifyThereIsOneStartPageTab()
            }
            .openMoreOptionsMenu(activityTestRule.activity) {
                mDevice.waitForIdle()
                verifyCloseAllTabsButton()
            }
            .closeAllTabs {
                verifyNoTabAddressView()
                checkNumberOfTabsTabCounter("1")
            }
        startPage { verifyStartPage() }
    }

    // This test verifies that close all tabs option works as expected
    @Test
    fun closeAllPrivateTabsTest() {
        navigationToolbar {}
            .openTabTrayMenu {
                openPrivateBrowsing()
            }
            .openNewTab {}
            .openTabTrayMenu {
                openPrivateBrowsing()
                verifyThereIsOnePrivateTabOpen()
            }
            .openMoreOptionsMenu(activityTestRule.activity) {
                mDevice.waitForIdle()
                verifyCloseAllPrivateTabsButton()
            }
            .closeAllPrivateTabs {}
            .openTabTrayMenu {
                openPrivateBrowsing()
                verifyThereAreNotPrivateTabsOpen()
                goBackFromTabTrayTest()
            }
    }

    // Closing the last tab leaves one start-page tab
    @Test
    fun closeOneTabXButtonTest() {
        val genericURL = TestAssetHelper.getGenericAsset(mockWebServer, 1)

        navigationToolbar {}.enterUrlAndEnterToBrowser(genericURL.url) { verifyPageContent(genericURL.content) }
        navigationToolbar {
            checkNumberOfTabsTabCounter("1")
        }
            .openTabTrayMenu {
                verifyExistingOpenTabs(genericURL.title)
            }
            .closeTabXButton {
                verifyNoTabAddressView()
                checkNumberOfTabsTabCounter("1")
            }
        startPage { verifyStartPage() }
    }

    // This test verifies the change between regular-private browsing works
    @Test
    fun privateRegularModeChangeTest() {
        navigationToolbar {}
            .openTabTrayMenu {
                openPrivateBrowsing()
                verifyPrivateBrowsingTab(true)
                verifyRegularBrowsingTab(false)
                openRegularBrowsing()
                verifyPrivateBrowsingTab(false)
                verifyRegularBrowsingTab(true)
                goBackFromTabTrayTest()
            }
    }

    // This test verifies the new tab is open and that its items are all in place
    @Ignore("Failing, see: https://github.com/mozilla-mobile/reference-browser/issues/2222")
    @Test
    fun openNewTabTest() {
        val genericURL = TestAssetHelper.getGenericAsset(mockWebServer, 1)

        navigationToolbar {}
            .openTabTrayMenu {}
            .openNewTab {
                // A blank page shows an empty address with the hint.
                verifyNoTabAddressView()
                checkNumberOfTabsTabCounter("1")
            }
            .openTabTrayMenu {}
            .openNewTab {}
            .enterUrlAndEnterToBrowser(genericURL.url) {
                verifyUrl(genericURL.url.toString())
            }
        navigationToolbar {
                checkNumberOfTabsTabCounter("2")
            }
            .openTabTrayMenu {
                verifyExistingOpenTabs("New tab")
                verifyExistingOpenTabs(genericURL.title)
            }
            .clickOpenTab("New tab") {}
        navigationToolbar { verifyNoTabAddressView() }
    }

    // This test verifies the new tab is open and that its items are all in place
    @Ignore("Failing, see: https://github.com/mozilla-mobile/reference-browser/issues/1923")
    @Test
    fun openNewPrivateTabTest() {
        val firstGenericURL = TestAssetHelper.getGenericAsset(mockWebServer, 1)
        val secondGenericURL = TestAssetHelper.getGenericAsset(mockWebServer, 2)

        navigationToolbar {}
            .openTabTrayMenu {
                openPrivateBrowsing()
            }
            .openNewTab {
                // The private page's own address is never shown.
                verifyNoTabAddressView()
                checkNumberOfTabsTabCounter("1")
            }
            .openTabTrayMenu {
                openPrivateBrowsing()
            }
            .openNewTab {}
            .enterUrlAndEnterToBrowser(firstGenericURL.url) {
                verifyPageContent("Page content: 1")
            }
        navigationToolbar {
                checkNumberOfTabsTabCounter("2")
            }
            .openTabTrayMenu {
                openPrivateBrowsing()
                verifyExistingOpenTabs("Private Browsing")
                verifyExistingOpenTabs(firstGenericURL.title)
            }
            .openNewTab {}
            .enterUrlAndEnterToBrowser(secondGenericURL.url) {
                verifyPageContent("Page content: 2")
            }
        navigationToolbar {
            checkNumberOfTabsTabCounter("3")
        }
    }

    // This test verifies the back button functionality
    @Test
    fun goBackFromTabTrayTest() {
        navigationToolbar {}
            .openTabTrayMenu {}
            .goBackFromTabTray {
                // For now checking new tab is valid, this will change when browsing to/from different places
                verifyNoTabAddressView()
            }
    }
}
