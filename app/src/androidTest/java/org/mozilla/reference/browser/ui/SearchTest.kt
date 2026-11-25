/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ui

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import mockwebserver3.MockWebServer
import mozilla.components.browser.state.action.ContentAction
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.helpers.AndroidAssetDispatcher
import org.mozilla.reference.browser.helpers.BrowserActivityTestRule
import org.mozilla.reference.browser.helpers.RetryTestRule
import org.mozilla.reference.browser.helpers.TestAssetHelper
import org.mozilla.reference.browser.helpers.TestHelper
import org.mozilla.reference.browser.ui.robots.navigationToolbar

class SearchTest {
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
    fun siteSearchSuggestionTest() {
        val defaultWebPage = TestAssetHelper.getGenericAsset(mockWebServer, 1)

        navigationToolbar {}.enterUrlAndEnterToBrowser(defaultWebPage.url) {}
        navigationToolbar {}
            .openTabTrayMenu {}
            .openNewTab {}
            .clickToolbar {
                typeText("generic1.html")
                verifySearchSuggestion(defaultWebPage.title)
            }
            .clickSearchSuggestion(defaultWebPage.title) {
                verifyUrl(defaultWebPage.url.toString())
            }
    }

    @Test
    fun typedAddressSurvivesTabUpdatesTest() {
        val defaultWebPage = TestAssetHelper.getGenericAsset(mockWebServer, 1)

        navigationToolbar {}.enterUrlAndEnterToBrowser(defaultWebPage.url) {}
        navigationToolbar {}
            .clickToolbar {
                typeText("zqxjk")
                // A later update of the tab, like the history state GeckoView saves seconds after a load, used to
                // replace what was typed with the tab's (empty) search terms.
                val store = TestHelper.appContext.components.core.store
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    store.dispatch(
                        ContentAction.UpdateTitleAction(checkNotNull(store.state.selectedTabId), "Updated title")
                    )
                }
                SystemClock.sleep(TAB_UPDATE_SETTLE_MS)
                verifyEditText("zqxjk")
            }
    }
}

private const val TAB_UPDATE_SETTLE_MS = 1000L
