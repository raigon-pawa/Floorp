/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ui

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import mockwebserver3.MockWebServer
import mozilla.components.browser.state.selector.selectedTab
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.helpers.AndroidAssetDispatcher
import org.mozilla.reference.browser.helpers.BrowserActivityTestRule
import org.mozilla.reference.browser.helpers.RetryTestRule
import org.mozilla.reference.browser.helpers.TestAssetHelper
import org.mozilla.reference.browser.ui.robots.downloadRobot
import org.mozilla.reference.browser.ui.robots.navigationToolbar
import org.mozilla.reference.browser.ui.robots.notificationShade

class DownloadTest {
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
    fun cancelFileDownloadTest() {
        val downloadPage = TestAssetHelper.getDownloadAsset(mockWebServer)

        navigationToolbar {}.enterUrlAndEnterToBrowser(downloadPage.url) {}

        downloadRobot {
            cancelDownload()
        }

        // Checked in the store: on Android 13 and later the app may not post the notification this test used to look
        // for, so its absence proved nothing. Give a wrongly started download the time to show up.
        SystemClock.sleep(SETTLE_MS)
        val store = InstrumentationRegistry.getInstrumentation().targetContext.components.core.store
        val started = store.state.downloads.values.filter { it.fileName?.startsWith("web_icon") == true }
        assertTrue("a cancelled download started: $started", started.isEmpty())
        assertNull("the download request is still pending", store.state.selectedTab?.content?.download)
    }

    @Ignore("Disabled - https://github.com/mozilla-mobile/reference-browser/issues/2130")
    @Test
    fun fileDownloadTest() {
        val downloadPage = TestAssetHelper.getDownloadAsset(mockWebServer)
        val downloadFileName = "web_icon.png"

        navigationToolbar {}.enterUrlAndEnterToBrowser(downloadPage.url) {}

        downloadRobot {
            confirmDownload()
        }

        notificationShade {
            verifyDownloadNotificationExist("Download completed", downloadFileName)
        }
            .closeNotification {}
    }

    private companion object {
        const val SETTLE_MS = 3_000L
    }
}
