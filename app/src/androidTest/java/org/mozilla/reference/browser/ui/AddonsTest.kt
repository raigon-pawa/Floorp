/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ui

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.platform.app.InstrumentationRegistry
import mozilla.components.feature.addons.R as addonsR
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mozilla.reference.browser.helpers.BrowserActivityTestRule
import org.mozilla.reference.browser.ui.robots.navigationToolbar

/**
 * The add-ons manager. These tests need the live addons.mozilla.org (the recommended list and the uBlock Origin
 * download) and are skipped without a validated network connection. No RetryTestRule: a retry would start with the
 * add-on a failed attempt installed.
 */
class AddonsTest {
    @get:Rule val activityTestRule = BrowserActivityTestRule()

    @Before
    fun requireNetwork() {
        val connectivity =
            InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        assumeTrue(
            "AddonsTest needs addons.mozilla.org",
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
    }

    @Test
    fun addonsListingPageTest() {
        navigationToolbar {}
            .openThreeDotMenu {}
            .openAddonsManager {
                verifyAddonsRecommendedView()
            }
    }

    @Test
    fun cancelAddonInstallTest() {
        val addonName = "uBlock Origin"

        navigationToolbar {}
            .openThreeDotMenu {}
            .openAddonsManager {
                clickInstallAddonButton(addonName)
                verifyInstallAddonPrompt(addonName)
                clickCancelInstallButton()
                verifyAddonsRecommendedView()
            }
    }

    @Test
    fun installAddonTest() {
        val addonName = "uBlock Origin"

        navigationToolbar {}
            .openThreeDotMenu {}
            .openAddonsManager {
                clickInstallAddonButton(addonName)
                verifyInstallAddonPrompt(addonName)
                clickAllowInstallAddonButton()
                waitForAddonDownloadComplete()
                verifyAddonDownloadCompletedPrompt(addonName)
            }
    }

    @Test
    fun verifyAddonElementsTest() {
        val addonName = "uBlock Origin"

        navigationToolbar {}
            .openThreeDotMenu {}
            .openAddonsManager {
                verifyAddonsRecommendedView()
                clickInstallAddonButton(addonName)
                clickAllowInstallAddonButton()
                waitForAddonDownloadComplete()
                dismissAddonDownloadCompletedPrompt(addonName)
                openAddon(addonName)
                verifyAddonElementsView(addonName)
            }
    }

    @Test
    fun removeAddonTest() {
        val addonName = "uBlock Origin"

        navigationToolbar {}
            .openThreeDotMenu {}
            .openAddonsManager {
                verifyAddonsRecommendedView()
                clickInstallAddonButton(addonName)
                clickAllowInstallAddonButton()
                waitForAddonDownloadComplete()
                dismissAddonDownloadCompletedPrompt(addonName)
                openAddon(addonName)
                clickRemoveAddonButton()
                verifyAddonsRecommendedView()
            }
    }

    @Test
    fun disableAndEnableAddonTest() {
        val addonName = "uBlock Origin"

        navigationToolbar {}
            .openThreeDotMenu {}
            .openAddonsManager {
                clickInstallAddonButton(addonName)
                clickAllowInstallAddonButton()
                waitForAddonDownloadComplete()
                dismissAddonDownloadCompletedPrompt(addonName)
                openAddon(addonName)
                clickEnableSwitch()
                goBack()
                verifyAddonInSection(addonName, addonsR.string.mozac_feature_addons_disabled_section)
                openAddon(addonName)
                clickEnableSwitch()
                goBack()
                verifyAddonInSection(addonName, addonsR.string.mozac_feature_addons_enabled)
            }
    }

    @Test
    fun findMoreExtensionsOpensAmoTest() {
        navigationToolbar {}
            .openThreeDotMenu {}
            .openAddonsManager {
                verifyAddonsRecommendedView()
                clickFindMoreExtensions()
            }
        navigationToolbar { verifyNewTabAddressView("addons.mozilla.org") }
    }
}
