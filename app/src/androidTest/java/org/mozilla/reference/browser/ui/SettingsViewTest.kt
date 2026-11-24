/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ui

import androidx.core.net.toUri
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.mozilla.reference.browser.helpers.BrowserActivityTestRule
import org.mozilla.reference.browser.helpers.RetryTestRule
import org.mozilla.reference.browser.ui.robots.navigationToolbar

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
                verifyPrivacyButton()
                verifyPrivacySummary()
                verifyOpenLinksInApps()
                verifyMakeDefaultBrowserButton()
                verifyJetpackComposeButton()
                verifyDeveloperToolsHeading()
                verifyRemoteDebugging()
                verifyCustomAddonCollectionButton()
                verifyMozillaHeading()
                verifyAboutReferenceBrowserButton()
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
    fun aboutReferenceBrowserTest() {
        navigationToolbar {}
            .openThreeDotMenu {}
            .openSettings {}
            .openAboutReferenceBrowser {
                verifyAboutBrowser()
            }
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
