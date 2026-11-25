/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.content.Intent
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import mozilla.components.browser.state.selector.selectedTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.helpers.BrowserActivityTestRule
import org.mozilla.reference.browser.helpers.TestHelper.createCustomTabIntent

/** Fires intents as another app could at the exported [IntentReceiverActivity] and checks what the browser opens. */
class IntentReceiverTest {
    @get:Rule val activityTestRule = BrowserActivityTestRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store = context.components.core.store

    private fun tabUrls() = store.state.tabs.map { it.content.url }

    private fun fire(intent: Intent) {
        intent.setClassName(context, IntentReceiverActivity::class.java.name)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        instrumentation.waitForIdleSync()
        // The receiver finishes at once; a start that comes before its task is gone only brings that task forward.
        SystemClock.sleep(SETTLE_RECEIVER)
    }

    private fun view(url: String) = Intent(Intent.ACTION_VIEW).setData(url.toUri())

    private fun waitFor(condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + TIMEOUT
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return true
            SystemClock.sleep(POLL)
        }
        return condition()
    }

    /** Fires each intent, then a web URL as a marker, and checks that only the marker opened a tab. */
    private fun assertNothingOpens(vararg intents: Intent) {
        intents.forEachIndexed { index, intent ->
            val before = tabUrls()
            val marker = "https://example.org/marker-$index-${SystemClock.uptimeMillis()}"
            fire(intent)
            fire(view(marker))
            assertTrue("marker did not open after $intent", waitFor { marker in tabUrls() })
            assertEquals("$intent opened something", before.size + 1, tabUrls().size)
        }
    }

    @Test
    fun webUrlsOpenInATab() {
        val https = "https://example.org/receiver-https"
        val http = "HTTP://example.org/receiver-http"
        fire(view(https))
        fire(view("  $http\n"))
        assertTrue(waitFor { https in tabUrls() })
        assertTrue(waitFor { tabUrls().any { it.endsWith("example.org/receiver-http") } })
    }

    @Test
    fun otherSchemesOpenNothing() {
        assertNothingOpens(
            view("javascript:alert(1)"),
            view("JavaScript:alert(1)"),
            view("file:///sdcard/Download/x.html"),
            view("content://media/external/file/1"),
            view("data:text/html,<h1>x</h1>"),
            view("about:config"),
            view("moz-extension://1234/page.html"),
            view("intent://example.org#Intent;scheme=https;end"),
            view("android-app://com.example/https/example.org"),
            view("java\tscript:alert(1)"),
            Intent(Intent.ACTION_MAIN).setData("about:config".toUri()),
        )
    }

    @Test
    fun sharedAddressesOfOtherSchemesOpenNothing() {
        assertNothingOpens(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "about:config"),
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "see data:text/html,x"),
        )
    }

    @Test
    fun aSharedWebAddressOpens() {
        val url = "https://example.org/receiver-shared"
        fire(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Look: $url"))
        assertTrue(waitFor { url in tabUrls() })
    }

    @Test
    fun aForgedSessionIdIsIgnored() {
        val url = "https://example.org/receiver-session"
        fire(view(url).putExtra("activeSessionId", "not-a-tab"))
        assertTrue(waitFor { store.state.selectedTab?.content?.url == url })
    }

    @Test
    fun aForgedWebAppManifestDoesNotHideTheToolbar() {
        val url = "https://example.org/receiver-manifest"
        val manifest =
            """{"name":"Forged","start_url":"https://example.org/","scope":"https://example.org/","display":"standalone"}"""
        fire(createCustomTabIntent(url).putExtra("mozilla.components.feature.pwa.EXTRA_WEB_APP_MANIFEST", manifest))

        assertTrue(waitFor { store.state.customTabs.any { it.content.url == url } })
        val device = UiDevice.getInstance(instrumentation)
        assertNotNull(device.wait(Until.findObject(By.res(context.packageName, "toolbar")), TIMEOUT))
        // A web app manifest whose scope covers the page would hide the toolbar right after the window opens.
        SystemClock.sleep(SETTLE)
        assertTrue("custom tab toolbar hidden", device.hasObject(By.res(context.packageName, "toolbar")))
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val POLL = 100L
        const val SETTLE = 3_000L
        const val SETTLE_RECEIVER = 1_000L
    }
}
