/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

@file:Suppress("ForbiddenComment")

package org.mozilla.reference.browser.ui

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import mockwebserver3.MockWebServer
import mozilla.components.browser.state.selector.selectedTab
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.mozilla.reference.browser.browser.StartPage
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.helpers.AndroidAssetDispatcher
import org.mozilla.reference.browser.helpers.BrowserActivityTestRule
import org.mozilla.reference.browser.helpers.RetryTestRule
import org.mozilla.reference.browser.helpers.TestAssetHelper
import org.mozilla.reference.browser.ui.robots.navigationToolbar

/**
 * Tests for verifying the main three dot menu options
 *
 * Including:
 * - Verify all menu items present
 * - Forward button navigates forward to a page
 * - Reload button refreshes page content
 * - Share button opens app overlay menu
 * - Desktop site toggle forwards to desktop view of web page (TBD)
 * - Find in page button can locate web page text
 * - Open settings button opens Settings sub-menu
 *
 * Not included:
 * - TODO: Desktop site (user mockWebServer to parse request headers)
 * - Stop button stops page loading (covered by smoke tests)
 */
class ThreeDotMenuTest {
    private val mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
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
    fun homeScreenMenuTest() {
        navigationToolbar {}
            .openThreeDotMenu {
                verifyThreeDotMenuExists()
                // The start page's tab has the navigation row (its buttons disabled until there is somewhere to go)...
                verifyBackButtonExists()
                verifyForwardButtonExists()
                verifyReloadButtonExists()
                verifyStopButtonDoesntExist()
                verifyShareButtonExists()
                verifyBackForwardAndShareDisabled()
                // ...but nothing that only makes sense for a web page.
                verifyRequestDesktopSiteToggleDoesntExist()
                verifyAddToHomescreenButtonDoesntExist()
                verifyFindInPageButtonDoesntExist()
                verifyNewTabButtonExists()
                verifyNewPrivateTabButtonExists()
                verifyAddOnsButtonExists()
                verifyOpenSettingsExists()
            }
    }

    @Test
    fun threeDotMenuItemsTest() {
        val defaultWebPage = TestAssetHelper.getGenericAsset(mockWebServer, 1)
        navigationToolbar {
                // pull up URL to ensure this is not a first-user 3 dot menu
            }
            .enterUrlAndEnterToBrowser(defaultWebPage.url) {
                mDevice.waitForIdle()
            }
        navigationToolbar {}
            .openThreeDotMenu {
                verifyThreeDotMenuExists()
                verifyBackButtonExists()
                verifyForwardButtonExists()
                // Reload and Stop share one slot; the page has finished loading.
                verifyReloadButtonExists()
                verifyStopButtonDoesntExist()
                verifyShareButtonExists()
                verifyNewTabButtonExists()
                verifyNewPrivateTabButtonExists()
                verifyRequestDesktopSiteToggleExists()
                verifyAddToHomescreenButtonExists()
                verifyFindInPageButtonExists()
                verifyAddOnsButtonExists()
                verifyOpenSettingsExists()
            }
    }

    @Test
    fun newTabAndNewPrivateTabTest() {
        val store = InstrumentationRegistry.getInstrumentation().targetContext.components.core.store
        val defaultWebPage = TestAssetHelper.getGenericAsset(mockWebServer, 1)
        navigationToolbar {}.enterUrlAndEnterToBrowser(defaultWebPage.url) {}
        val pageTab = store.state.selectedTabId

        navigationToolbar {}.openThreeDotMenu {}.openNewTab {}
        val newTab = store.state.selectedTab
        assertNotEquals(pageTab, newTab?.id)
        assertTrue("New tab is no start page", newTab?.let(StartPage::isStartTab) ?: false)
        assertFalse("New tab opened a private tab", newTab?.content?.private ?: true)

        navigationToolbar {}.openThreeDotMenu {}.openNewPrivateTab {}
        val privateTab = store.state.selectedTab
        assertNotEquals(newTab?.id, privateTab?.id)
        assertTrue("New private tab opened a normal tab", privateTab?.content?.private ?: false)
    }

    @Test
    fun normalBrowsingTabNavigationTest() {
        val defaultWebPage = TestAssetHelper.getGenericAsset(mockWebServer, 1)
        val nextWebPage = TestAssetHelper.getGenericAsset(mockWebServer, 2)

        navigationToolbar {}
            .enterUrlAndEnterToBrowser(defaultWebPage.url) {
                verifyPageContent("Page content: 1")
            }
        navigationToolbar {}
            .enterUrlAndEnterToBrowser(nextWebPage.url) {
                verifyPageContent("Page content: 2")
            }
            .goBack {
                verifyPageContent("Page content: 1")
            }
        navigationToolbar {}
            .openThreeDotMenu {}
            .goForward {
                verifyPageContent("Page content: 2")
            }
    }

    @Ignore("Failing, see: https://github.com/mozilla-mobile/reference-browser/issues/2085")
    @Test
    fun privateBrowsingTabNavigationTest() {
        val defaultWebPage = TestAssetHelper.getGenericAsset(mockWebServer, 1)
        val nextWebPage = TestAssetHelper.getGenericAsset(mockWebServer, 2)

        navigationToolbar {}
            .openTabTrayMenu {
                openPrivateBrowsing()
            }
            .openNewTab {}
            .enterUrlAndEnterToBrowser(defaultWebPage.url) {
                verifyUrl(defaultWebPage.url.toString())
            }
        navigationToolbar {}
            .enterUrlAndEnterToBrowser(nextWebPage.url) {
                verifyUrl(nextWebPage.url.toString())
            }
            .goBack {
                verifyUrl(defaultWebPage.url.toString())
            }
        navigationToolbar {}
            .openThreeDotMenu {}
            .goForward {
                verifyUrl(nextWebPage.url.toString())
            }
    }

    // need to add clear cache setup to ensure correct starting page
    // also, investigate why this periodically causes mockWebServer to crash
    @Test
    @Ignore("https://github.com/mozilla-mobile/reference-browser/issues/1314")
    fun refreshPageTest() {
        val refreshWebPage = TestAssetHelper.getRefreshAsset(mockWebServer)

        navigationToolbar {
                // load the default page, to be refreshed
                // (test assumes no cookies cached at test start)
            }
            .enterUrlAndEnterToBrowser(refreshWebPage.url) {
                verifyPageContent("DEFAULT")
            }
        navigationToolbar {}
            .openThreeDotMenu {
                // refresh page and verify
            }
            .refreshPage {
                verifyPageContent("REFRESHED")
            }
    }

    @Test
    fun doShareTest() {
        val genericURL = TestAssetHelper.getGenericAsset(mockWebServer, 1)

        navigationToolbar {}.enterUrlAndEnterToBrowser(genericURL.url) {}
        navigationToolbar {}
            .openThreeDotMenu {}
            .openShare {
                verifyShareContentPanel()
            }
    }

    @Test
    fun findInPageTest() {
        val defaultWebPage = TestAssetHelper.getGenericAsset(mockWebServer, 1)

        navigationToolbar {}
            .enterUrlAndEnterToBrowser(defaultWebPage.url) {
                verifyPageContent("Page content: 1")
            }
        navigationToolbar {}
            .openThreeDotMenu {}
            .openFindInPage {
                verifyFindInPageBar()
                enterFindInPageQuery("e")
                verifyFindInPageResult("1/2")
                clickFindInPageNextButton()
                verifyFindInPageResult("2/2")
                clickFindInPagePreviousButton()
                verifyFindInPageResult("1/2")
                clickFindInPageCloseButton()
                verifyFindInPageBarIsDismissed()
            }
    }

    @Test
    fun openSettingsTest() {
        navigationToolbar {}
            .openThreeDotMenu {}
            .openSettings {
                verifySettingsViewExists()
            }
    }

    @Ignore("Failing with frequent ANR: https://bugzilla.mozilla.org/show_bug.cgi?id=1764605")
    @Test
    fun requestDesktopSiteTest() {
        val defaultWebPage = TestAssetHelper.getGenericAsset(mockWebServer, 1)

        navigationToolbar {}.enterUrlAndEnterToBrowser(defaultWebPage.url) {}
        navigationToolbar {}
            .openThreeDotMenu {}
            .switchRequestDesktopSiteToggle {}
            .openThreeDotMenu {
                verifyRequestDesktopSiteIsTurnedOn()
            }
            .goBack {}
            .openThreeDotMenu {}
            .switchRequestDesktopSiteToggle {}
            .openThreeDotMenu {
                verifyRequestDesktopSiteIsTurnedOff()
            }
    }

    @Test
    fun addToHomeScreenTest() {
        val defaultWebPage = TestAssetHelper.getGenericAsset(mockWebServer, 1)

        navigationToolbar {}.enterUrlAndEnterToBrowser(defaultWebPage.url) {}
        navigationToolbar {}
            .openThreeDotMenu {}
            .openAddToHomeScreen {
                clickCancelAddToHomeScreenButton()
            }

        navigationToolbar {}
            .openThreeDotMenu {}
            .openAddToHomeScreen {
                clickSystemHomeScreenShortcutAddButton()
            }
            .openHomeScreenShortcut(defaultWebPage.title) {
                verifyUrl(defaultWebPage.url.toString())
            }
    }
}
