/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ui

import androidx.core.net.toUri
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mozilla.reference.browser.helpers.AndroidAssetDispatcher
import org.mozilla.reference.browser.helpers.BrowserActivityTestRule
import org.mozilla.reference.browser.helpers.RetryTestRule
import org.mozilla.reference.browser.helpers.TestAssetHelper
import org.mozilla.reference.browser.ui.robots.navigationToolbar

class CrashRecoveryTest {
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

    // A crashed content process must leave the user with a way back to the page, not a blank tab.
    @Test
    fun restoreCrashedTabTest() {
        val page = TestAssetHelper.getGenericAsset(mockWebServer, 1)

        navigationToolbar {}.enterUrlAndEnterToBrowser(page.url) { verifyPageContent(page.content) }
        navigationToolbar {}
            .enterUrlAndEnterToBrowser("about:crashcontent".toUri()) {
                clickSnackbarRestoreButton()
                verifyPageContent(page.content)
                verifyNoTabCrashedSnackbar()
            }
    }

    // A crashed tab must not swallow what the user does next: typing an address has to leave the crashed page.
    @Test
    fun navigateAwayFromCrashedTabTest() {
        val page = TestAssetHelper.getGenericAsset(mockWebServer, 1)
        val nextPage = TestAssetHelper.getGenericAsset(mockWebServer, 2)

        navigationToolbar {}.enterUrlAndEnterToBrowser(page.url) { verifyPageContent(page.content) }
        navigationToolbar {}.enterUrlAndEnterToBrowser("about:crashcontent".toUri()) { verifyTabCrashedSnackbar() }
        navigationToolbar {}
            .enterUrlAndEnterToBrowser(nextPage.url) {
                verifyPageContent(nextPage.content)
                verifyNoTabCrashedSnackbar()
            }
    }
}
