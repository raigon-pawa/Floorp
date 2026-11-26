/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ui

import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import mockwebserver3.MockWebServer
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.feature.downloads.R as DownloadsR
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.helpers.AndroidAssetDispatcher
import org.mozilla.reference.browser.helpers.BrowserActivityTestRule
import org.mozilla.reference.browser.helpers.TestAssetHelper
import org.mozilla.reference.browser.helpers.TestAssetHelper.waitingTime
import org.mozilla.reference.browser.ui.robots.downloadRobot
import org.mozilla.reference.browser.ui.robots.mDevice
import org.mozilla.reference.browser.ui.robots.navigationToolbar

/**
 * AskFirstDownloadManager on Android 13 and later: with the notification permission not granted (each test starts from
 * cleared data), a confirmed download waits for the system prompt and starts after either answer. No RetryTestRule: a
 * retry would start with the permission already answered.
 */
class AskFirstDownloadTest {
    private lateinit var mockWebServer: MockWebServer

    @get:Rule val activityTestRule = BrowserActivityTestRule()

    private val store = InstrumentationRegistry.getInstrumentation().targetContext.components.core.store

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        mockWebServer =
            MockWebServer().apply {
                dispatcher = AndroidAssetDispatcher()
                start()
            }
    }

    @After
    fun tearDown() {
        if (::mockWebServer.isInitialized) runCatching { mockWebServer.close() }
    }

    @Test fun downloadStartsAfterNotificationsAreAllowed() = downloadAnswering("permission_allow_button")

    // Without notifications the finished download is announced in the browser, with Open.
    @Test
    fun downloadStartsAfterNotificationsAreDenied() {
        downloadAnswering("permission_deny_button")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fileName = store.state.downloads.values.first { it.isCompletedTestFile() }.fileName
        val announced = context.getString(R.string.archwave_download_completed, fileName)
        assertNotNull(
            "no snackbar for the finished download",
            mDevice.wait(Until.findObject(By.text(announced)), waitingTime),
        )
        val open = context.getString(DownloadsR.string.mozac_feature_downloads_button_open)
        assertNotNull("the snackbar has no Open action", mDevice.findObject(By.text(open)))
    }

    private fun DownloadState.isCompletedTestFile() =
        fileName?.startsWith(FILE_PREFIX) == true && status == DownloadState.Status.COMPLETED

    private fun completed() = store.state.downloads.values.any { it.isCompletedTestFile() }

    private fun downloadAnswering(button: String) {
        navigationToolbar {}.enterUrlAndEnterToBrowser(TestAssetHelper.getDownloadAsset(mockWebServer).url) {}
        downloadRobot { confirmDownload() }

        val answer = mDevice.wait(Until.findObject(By.res(PERMISSION_CONTROLLER, button)), waitingTime)
        assertNotNull("no notification prompt", answer)
        assertTrue("downloaded behind the prompt", !completed())
        answer.click()

        val deadline = SystemClock.uptimeMillis() + waitingTime
        while (!completed() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(POLL_MS)
        assertTrue("the download did not finish after the answer", completed())
    }

    private companion object {
        const val PERMISSION_CONTROLLER = "com.android.permissioncontroller"
        const val FILE_PREFIX = "web_icon"
        const val POLL_MS = 100L
    }
}
