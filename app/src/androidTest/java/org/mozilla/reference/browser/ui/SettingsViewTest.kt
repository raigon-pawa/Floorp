/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ui

import android.net.Uri
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import mockwebserver3.MockWebServer
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.browser.StartPage
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.helpers.AndroidAssetDispatcher
import org.mozilla.reference.browser.helpers.BrowserActivityTestRule
import org.mozilla.reference.browser.helpers.RetryTestRule
import org.mozilla.reference.browser.helpers.TestAssetHelper
import org.mozilla.reference.browser.helpers.TestHelper
import org.mozilla.reference.browser.settings.BrowsingDataCleaner
import org.mozilla.reference.browser.settings.ClearDataDialogFragment
import org.mozilla.reference.browser.settings.ClearableData
import org.mozilla.reference.browser.ui.robots.SettingsViewRobot
import org.mozilla.reference.browser.ui.robots.mDevice
import org.mozilla.reference.browser.ui.robots.navigationToolbar
import org.mozilla.reference.browser.ui.robots.startPage

/**
 * Tests for verifying the settings view options exist as expected:
 * - Appears when the settings submenu is tapped
 * - Expected options are displayed as listed below
 */
class SettingsViewTest {
    @get:Rule val browserActivityTestRule = BrowserActivityTestRule()

    @Rule @JvmField val retryTestRule = RetryTestRule(3)

    // This test verifies settings view items are all in place
    @Test
    fun settingsItemsTest() {
        navigationToolbar {}
            .openThreeDotMenu {}
            .openSettings {
                verifySettingsRecyclerViewToExist()
                verifyNavigateUp()
                verifyGeneralHeading()
                verifySearchEngineButton()
                verifyOpenLinksInApps()
                verifyMakeDefaultBrowserButton()
                verifyOpenLinksInPrivate()
                verifyPrivacyAndSecurityHeading()
                verifyPrivacyButton()
                verifyPrivacySummary()
                verifyClearBrowsingDataButton()
                verifyAdvancedHeading()
                verifyRemoteDebugging()
                verifyCustomAddonCollectionButton()
                verifyAboutHeading()
                verifyAboutArchwaveButton()
            }
    }

    @Test
    fun privacySettingsItemsTest() {
        navigationToolbar {}
            .openThreeDotMenu {}
            .openSettings {}
            .openSettingsViewPrivacy {
                verifyPrivacyUpButton()
                verifyPrivacySettings()
                verifyTrackingProtectionHeading()
                verifyTPEnableInNormalBrowsing()
                verifyTPEnableinPrivateBrowsing()
                // Fresh-install defaults
                verifySwitch(R.string.preferences_tracking_protection_normal, checked = true)
                verifySwitch(R.string.preferences_tracking_protection_private, checked = true)
                verifySwitch(R.string.preferences_https_only, checked = true)
                verifyDohDefault()
                verifySwitch(R.string.preferences_global_privacy_control, checked = true)
                verifySwitch(R.string.preferences_search_suggestions, checked = false)
            }
    }

    @Test
    fun setDefaultBrowserTest() {
        navigationToolbar {}
            .openThreeDotMenu {}
            .openSettings {}
            .makeDefaultBrowser {
                verifyAndroidDefaultApps()
            }
    }

    @Test
    fun remoteDebuggingViaUSB() {
        navigationToolbar {}
            .openThreeDotMenu {}
            .openSettings {
                toggleRemoteDebuggingOn()
                toggleRemoteDebuggingOff()
                toggleRemoteDebuggingOn()
            }
    }

    @Test
    fun aboutArchwaveTest() {
        navigationToolbar {}
            .openThreeDotMenu {}
            .openSettings {}
            .openAboutArchwave {
                verifyAboutBrowser()
            }
    }

    @Test
    fun searchEnginePickerTest() {
        val store = TestHelper.appContext.components.core.store
        navigationToolbar {}
            .openThreeDotMenu {}
            .openSettings {
                // Fresh install
                verifySearchEngine("DuckDuckGo")
                selectSearchEngine("Wikipedia")
                verifySearchEngine("Wikipedia")
                assertEquals("Wikipedia", store.state.search.selectedOrDefaultSearchEngine?.name)
                selectSearchEngine("DuckDuckGo")
                verifySearchEngine("DuckDuckGo")
                assertEquals("DuckDuckGo", store.state.search.selectedOrDefaultSearchEngine?.name)
            }
    }

    @Test
    fun clearBrowsingDataDialogTest() {
        navigationToolbar {}
            .openThreeDotMenu {}
            .openSettings {
                clickClearBrowsingData()
                ClearableData.entries.forEach { verifyClearDataItem(it.label, checked = it.checkedByDefault) }
                verifyClearButtonEnabled(true)
                ClearableData.entries.filter { it.checkedByDefault }.forEach { toggleClearDataItem(it.label) }
                verifyClearButtonEnabled(false)
                toggleClearDataItem(R.string.archwave_clear_data_history)
                verifyClearButtonEnabled(true)
            }
    }

    @Test
    fun clearHistoryRemovesSuggestionTest() {
        val mockWebServer =
            MockWebServer().apply {
                dispatcher = AndroidAssetDispatcher()
                start()
            }
        try {
            val visited = TestAssetHelper.getGenericAsset(mockWebServer, 1)
            val current = TestAssetHelper.getGenericAsset(mockWebServer, 2)
            navigationToolbar {}.enterUrlAndEnterToBrowser(visited.url) {}
            // The tab moves on, so only the history still knows the first page.
            navigationToolbar {}.enterUrlAndEnterToBrowser(current.url) {}
            navigationToolbar {}
                .clickToolbar {
                    clickClearToolbarButton() // The edit field starts with the tab's address.
                    typeText("generic1.html")
                    // By address: the title may not be stored yet when the tab moves on.
                    verifySearchSuggestion(visited.url.toString())
                }
            mDevice.pressBack()
            navigationToolbar {}
                .openThreeDotMenu {}
                .openSettings {
                    clickClearBrowsingData()
                    confirmClearData()
                }
                .goBack {}
            // The source the suggestion comes from, checked once clearing has finished: the UI check below only
            // waits a short while, so a suggestion that is just slower to appear would pass it.
            val history = InstrumentationRegistry.getInstrumentation().targetContext.components.core.historyStorage
            assertTrue(
                "history still suggests the page",
                history.getSuggestions("generic1.html", 10).none { it.url == visited.url.toString() },
            )
            navigationToolbar {}
                .clickToolbar {
                    clickClearToolbarButton() // The edit field starts with the tab's address.
                    typeText("generic1.html")
                    verifyNoSearchSuggestion(visited.url.toString())
                }
        } finally {
            runCatching { mockWebServer.close() }
        }
    }

    @Test
    fun clearOpenTabsLeavesOneStartTabTest() {
        withPage { page ->
            navigationToolbar {}.enterUrlAndEnterToBrowser(page) {}
            navigationToolbar {}
                .openThreeDotMenu {}
                .openSettings {
                    clickClearBrowsingData()
                    toggleClearDataItem(ClearableData.TABS.label)
                    confirmClearData()
                }
                .goBack {}
            verifyOneStartTab()
        }
    }

    // The prompt BrowserActivity shows at start for a clear the process did not finish: here the browser screen, and
    // with it EnsureTabFeature, is running while the tabs go, so a second replacement tab would show up.
    @Test
    fun interruptedClearOfOpenTabsLeavesOneStartTabTest() {
        withPage { page ->
            navigationToolbar {}.enterUrlAndEnterToBrowser(page) {}
            BrowsingDataCleaner.setPending(TestHelper.appContext, setOf(ClearableData.TABS))
            val activity = browserActivityTestRule.activity
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ClearDataDialogFragment.showIfInterrupted(activity, activity.supportFragmentManager)
            }
            SettingsViewRobot().confirmClearData()
            verifyOneStartTab()
        }
    }

    private fun withPage(test: (Uri) -> Unit) {
        val mockWebServer =
            MockWebServer().apply {
                dispatcher = AndroidAssetDispatcher()
                start()
            }
        try {
            test(TestAssetHelper.getGenericAsset(mockWebServer, 1).url)
        } finally {
            runCatching { mockWebServer.close() }
        }
    }

    private fun verifyOneStartTab() {
        startPage { verifyStartPage() }
        var urls = emptyList<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            urls = TestHelper.appContext.components.core.store.state.tabs.map { it.content.url }
        }
        assertEquals(listOf(StartPage.URL), urls)
    }

    /* Can't check further because after creating the custom add-on collection
    the currently running process is terminated see:
    /blob/master/app/src/main/java/org/mozilla/reference/browser/settings/SettingsFragment.kt#L217
    Confirming the custom add-on collection creation or trying to continue testing afterwards
    will cause the test instrumentation process to crash */
    @Test
    fun customAddonsCollectionTest() {
        navigationToolbar {}
            .openThreeDotMenu {}
            .openSettings {
                verifyCustomAddonCollectionButton()
                clickCustomAddonCollectionButton()
                verifyCustomAddonCollectionPanelExist()
            }
    }

    @Ignore("Failing, see: https://github.com/mozilla-mobile/reference-browser/issues/2260")
    @Test
    fun openLinksInAppsTest() {
        val url = "m.youtube.com"
        navigationToolbar {}
            .openThreeDotMenu {}
            .openSettings {
                verifyOpenLinksInApps()
                clickOpenLinksInApps()
            }
            .goBack {}
            .enterUrlAndEnterToBrowser(url.toUri()) {
                clickOpenInAppPromptButton()
            }
            .checkExternalApps {
                verifyYouTubeApp()
            }
    }
}
