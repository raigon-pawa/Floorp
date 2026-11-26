/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import android.provider.Browser
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import mozilla.components.concept.engine.EngineSession.LoadUrlFlags
import mozilla.components.feature.intent.ext.EXTRA_SESSION_ID
import mozilla.components.feature.intent.processing.TabIntentProcessor
import mozilla.components.feature.search.ext.buildSearchUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.helpers.BrowserActivityTestRule
import org.mozilla.reference.browser.helpers.TestHelper.createCustomTabIntent
import org.mozilla.reference.browser.settings.SettingsActivity

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
            // Android's regexes fold case by Unicode, where U+017F matches "s" in a case-insensitive pattern.
            view("http\u017f://example.org"),
            view("http:javascript:alert(1)"),
            Intent(Intent.ACTION_MAIN).setData("about:config".toUri()),
        )
    }

    @Test
    fun sharedTextWithAddressesOfOtherSchemesOpensASearch() {
        assertTrue(waitFor { store.state.search.selectedOrDefaultSearchEngine != null })
        val host = requireNotNull(store.state.search.selectedOrDefaultSearchEngine).buildSearchUrl("x").toUri().host
        // The search engine may redirect, so compare the decoded query.
        fun searched(text: String) =
            tabUrls().any { it.toUri().host == host && it.toUri().getQueryParameter("q") == text }
        listOf("about:config", "see data:text/html,x", "how to use std::vector in c++").forEach { text ->
            fire(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))
            assertTrue("no search for $text in ${tabUrls()}", waitFor { searched(text) })
        }
        assertTrue(tabUrls().none { it == "about:config" || it == "data:text/html,x" })
    }

    @Test
    fun aSharedWebAddressOpens() {
        val url = "https://example.org/receiver-shared"
        fire(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Look: $url"))
        assertTrue(waitFor { url in tabUrls() })
        // The finder returns "note:see" first, as it has a scheme.
        val after = "https://example.org/receiver-shared-after"
        fire(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "note:see $after"))
        assertTrue(waitFor { after in tabUrls() })
    }

    @Test
    fun linksOpenInAPrivateTabWhenSetTo() {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(context.getString(R.string.pref_key_open_links_in_private), true)
        }
        val url = "https://example.org/receiver-private"
        fire(view(url))
        assertTrue(waitFor { url in tabUrls() })
        assertTrue("opened in a normal tab", store.state.tabs.first { it.content.url == url }.content.private)
    }

    @Test
    fun customTabsOpenPrivatelyWhenLinksDo() {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(context.getString(R.string.pref_key_open_links_in_private), true)
        }
        val url = "https://example.org/receiver-private-custom-tab"
        fire(createCustomTabIntent(url))
        assertTrue(waitFor { store.state.customTabs.any { it.content.url == url } })
        assertTrue("opened a normal custom tab", store.state.customTabs.first { it.content.url == url }.content.private)
    }

    private fun resumedBrowserActivity(): BrowserActivity? {
        var resumed: BrowserActivity? = null
        instrumentation.runOnMainSync {
            resumed =
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<BrowserActivity>()
                    .firstOrNull()
        }
        return resumed
    }

    @Test
    fun theLauncherIconKeepsTheScreenOnTopOfTheBrowser() {
        context.startActivity(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(waitFor { resumed<SettingsActivity>() })

        fire(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))

        assertTrue("the launcher icon closed Settings", waitFor { resumed<SettingsActivity>() })
    }

    private inline fun <reified T : Activity> resumed(): Boolean {
        var found = false
        instrumentation.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is T }
        }
        return found
    }

    @Test
    fun aForgedSessionIdIsNotForwarded() {
        val url = "https://example.org/receiver-session"
        val other = "https://example.org/receiver-session-other"
        fire(view(other))
        assertTrue(waitFor { other in tabUrls() })
        val otherTab = store.state.tabs.first { it.content.url == other }.id
        // A running BrowserActivity takes the intent in onNewIntent, which never reads the id; a new one builds its
        // browser for the tab of that id.
        val old = activityTestRule.activity
        instrumentation.runOnMainSync { old.finish() }
        assertTrue(waitFor { old.isDestroyed })

        fire(view(url).putExtra(EXTRA_SESSION_ID, otherTab))
        assertTrue(waitFor { resumedBrowserActivity() != null })
        assertNull(resumedBrowserActivity()?.intent?.getStringExtra(EXTRA_SESSION_ID))
        assertTrue(waitFor { store.state.selectedTab?.content?.url == url })
    }

    @Test
    fun loadFlagsFromTheCallerAreIgnored() {
        val url = "https://example.org/receiver-flags"
        fire(view(url).putExtra(TabIntentProcessor.EXTRA_APP_LINK_LAUNCH_TYPE, LoadUrlFlags.BYPASS_CLASSIFIER))
        assertTrue(waitFor { url in tabUrls() })
        val tab = store.state.tabs.first { it.content.url == url }
        assertEquals(LoadUrlFlags.external().value, tab.engineState.initialLoadFlags.value)
    }

    @Test
    fun aCustomTabWithHeadersThatAreNotStringsOpens() {
        val url = "https://example.org/receiver-headers"
        fire(createCustomTabIntent(url).putExtra(Browser.EXTRA_HEADERS, Bundle().apply { putInt("a", 1) }))
        assertTrue(waitFor { store.state.customTabs.any { it.content.url == url } })
    }

    @Test
    fun aForgedWebAppManifestDoesNotHideTheToolbar() {
        val url = "https://example.org/receiver-manifest"
        val manifest =
            """{"name":"Forged","start_url":"https://example.org/","scope":"https://example.org/",""" +
                """"display":"standalone"}"""
        fire(createCustomTabIntent(url).putExtra("mozilla.components.feature.pwa.EXTRA_WEB_APP_MANIFEST", manifest))

        assertTrue(waitFor { store.state.customTabs.any { it.content.url == url } })
        val device = UiDevice.getInstance(instrumentation)
        assertNotNull(device.wait(Until.findObject(By.res(context.packageName, "toolbar")), TIMEOUT))
        // A web app manifest whose scope covers the page would hide the toolbar right after the window opens.
        SystemClock.sleep(SETTLE)
        assertTrue("custom tab toolbar hidden", device.hasObject(By.res(context.packageName, "toolbar")))
    }

    /** An extra holding a class only another app has: unparcelling it throws. */
    private fun foreignParcelable(key: String): Bundle {
        val parcel = Parcel.obtain()
        try {
            Bundle().apply { putParcelable(key, Marker()) }.writeToParcel(parcel, 0)
            val known = Marker::class.java.name.toByteArray(Charsets.UTF_16LE)
            val foreign = Marker::class.java.name.replace("Marker", "Xarker").toByteArray(Charsets.UTF_16LE)
            val bytes = parcel.marshall()
            val at = (0..bytes.size - known.size).first { i -> known.indices.all { bytes[i + it] == known[it] } }
            foreign.copyInto(bytes, at)
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            return Bundle.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun extrasWithAForeignClassAreDropped() {
        val url = "https://example.org/receiver-foreign"
        fire(view(url).putExtras(foreignParcelable("x")))
        assertTrue(waitFor { url in tabUrls() })
        fire(createCustomTabIntent("$url-headers").putExtra(Browser.EXTRA_HEADERS, foreignParcelable("a")))
        assertTrue(waitFor { store.state.customTabs.any { it.content.url == "$url-headers" } })
        assertNothingOpens(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtras(foreignParcelable(Intent.EXTRA_TEXT))
        )
    }

    /** Stands in for a class of another app. */
    class Marker : Parcelable {
        override fun describeContents() = 0

        override fun writeToParcel(dest: Parcel, flags: Int) = Unit

        /** Unparcels a [Marker]. */
        companion object CREATOR : Parcelable.Creator<Marker> {
            override fun createFromParcel(source: Parcel) = Marker()

            override fun newArray(size: Int) = arrayOfNulls<Marker>(size)
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val POLL = 100L
        const val SETTLE = 3_000L
        const val SETTLE_RECEIVER = 1_000L
    }
}
