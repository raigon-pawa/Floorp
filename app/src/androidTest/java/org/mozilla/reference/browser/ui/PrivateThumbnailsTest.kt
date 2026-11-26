/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ui

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import mozilla.components.browser.state.selector.findTab
import mozilla.components.concept.base.images.ImageLoadRequest
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.helpers.AndroidAssetDispatcher
import org.mozilla.reference.browser.helpers.BrowserActivityTestRule
import org.mozilla.reference.browser.helpers.TestAssetHelper
import org.mozilla.reference.browser.ui.robots.navigationToolbar

/** Private browsing leaves no pictures of its pages: the thumbnail of a private tab is never kept. */
class PrivateThumbnailsTest {
    private lateinit var mockWebServer: MockWebServer
    private val components = InstrumentationRegistry.getInstrumentation().targetContext.components
    private val store = components.core.store

    @get:Rule val activityTestRule = BrowserActivityTestRule()

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

    private fun waitFor(condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + TestAssetHelper.waitingTime
        while (!condition() && SystemClock.uptimeMillis() < end) SystemClock.sleep(POLL_MS)
        return condition()
    }

    private fun thumbnail(tabId: String, private: Boolean) = runBlocking {
        components.core.thumbnailStorage.loadThumbnail(ImageLoadRequest(tabId, SIZE_PX, private)).await()
    }

    @Test
    fun privateTabsGetNoThumbnail() {
        val tabs = components.useCases.tabsUseCases
        // A normal tab gets one once its page has loaded, so the private tab below had the same chance.
        val normalTab = tabs.addTab(TestAssetHelper.getGenericAsset(mockWebServer, 1).url.toString(), selectTab = true)
        assertTrue("no thumbnail for a normal tab", waitFor { thumbnail(normalTab, private = false) != null })

        val privateTab =
            tabs.addTab(
                TestAssetHelper.getGenericAsset(mockWebServer, 2).url.toString(),
                selectTab = true,
                private = true,
            )
        assertTrue(waitFor { store.state.findTab(privateTab)?.content?.loading == false })
        // Opening the tray takes a fresh picture of the tab being left, which goes to the persistent cache. Once the
        // page has painted: before that there is no picture to take.
        SystemClock.sleep(SETTLE_MS)
        navigationToolbar {}.openTabTrayMenu {}
        SystemClock.sleep(SETTLE_MS)
        assertNull("kept a thumbnail of a private tab", thumbnail(privateTab, private = true))
        assertNull("kept a private tab's thumbnail in the normal cache", thumbnail(privateTab, private = false))
    }

    private companion object {
        const val POLL_MS = 100L
        const val SETTLE_MS = 3_000L
        const val SIZE_PX = 64
    }
}
