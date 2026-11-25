/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Browser
import androidx.core.net.toUri
import mozilla.components.feature.intent.ext.EXTRA_SESSION_ID
import mozilla.components.feature.intent.ext.sanitize
import mozilla.components.feature.intent.processing.TabIntentProcessor
import mozilla.components.feature.media.service.AbstractMediaSessionService
import mozilla.components.support.ktx.kotlin.isUrl
import mozilla.components.support.utils.WebURLFinder
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.settings.Settings

/**
 * Entry point for launcher, link, share, custom tab and PWA shortcut intents. It has no UI: the intent processors pick
 * the target and it forwards to [BrowserActivity] or [ExternalAppBrowserActivity].
 */
class IntentReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = (intent?.let { Intent(it) } ?: Intent()).sanitize()
        restrictToWhatOtherAppsMaySend(intent)

        // Tapping the media notification brings back the tab that plays.
        if (intent.action == AbstractMediaSessionService.ACTION_SWITCH_TAB && isFromThisApp()) {
            intent
                .getStringExtra(AbstractMediaSessionService.EXTRA_TAB_ID)
                ?.takeIf { id -> components.core.store.state.tabs.any { it.id == id } }
                ?.let { components.useCases.tabsUseCases.selectTab(it) }
        }

        val utils = components.utils

        val private = Settings.isOpenLinksInPrivate(this)
        val processors = if (private) utils.privateIntentProcessors else utils.intentProcessors
        val external = if (private) utils.privateExternalIntentProcessors else utils.externalIntentProcessors
        val processor = processors.firstOrNull { it.process(intent) }

        val className =
            if (processor in external) {
                ExternalAppBrowserActivity::class
            } else {
                BrowserActivity::class
            }

        intent.setClassName(applicationContext, className.java.name)

        startActivity(intent)
        finish()
    }

    /**
     * This activity is exported, so anything may arrive here. The activities it forwards to are not exported and trust
     * what they get, so only web addresses, shared text and the custom tabs protocol pass.
     */
    private fun restrictToWhatOtherAppsMaySend(intent: Intent) {
        // Only this app's own code may set these. The processors add the session id again for the tab they open.
        INTERNAL_EXTRAS.forEach(intent::removeExtra)
        // Never needed for what the app opens: a selector could redirect the start below to any activity of the app.
        intent.selector = null
        intent.clipData = null
        // The caller's flags (URI grants among them) would apply to the browser activity: a second task, a cleared task
        // or NO_HISTORY, which finishes the browser as soon as a file picker covers it. Only the one BrowserActivity
        // reads is kept; the processors set what they need (NEW_DOCUMENT for web apps).
        intent.flags = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
        // The custom tab processor throws on a header that is not a string, which would crash the app.
        intent.getBundleExtra(Browser.EXTRA_HEADERS)?.let { headers ->
            if (headers.keySet().any { headers.getString(it) == null }) intent.removeExtra(Browser.EXTRA_HEADERS)
        }

        val url = IncomingIntentRules.webUrlOrNull(intent.dataString)
        if (url != intent.dataString) intent.setDataAndType(url?.toUri(), intent.type)

        // The tab intent processor finds a URL in these the same ways.
        when (intent.action) {
            Intent.ACTION_SEND -> intent.restrictText(Intent.EXTRA_TEXT) { WebURLFinder(it).bestWebURL() }
            Intent.ACTION_SEARCH,
            Intent.ACTION_WEB_SEARCH -> intent.restrictText(SearchManager.QUERY) { it.takeIf(String::isUrl) }
        }
    }

    private fun Intent.restrictText(extra: String, findUrl: (String) -> String?) {
        val text = IncomingIntentRules.sharedTextOrNull(getStringExtra(extra), findUrl)
        if (text == null) removeExtra(extra) else putExtra(extra, text)
    }

    // shortcut: before Android 14 the launching app is unknown, so the tab id, a random UUID, is the only guard.
    private fun isFromThisApp() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || launchedFromUid == Process.myUid()

    private companion object {
        val INTERNAL_EXTRAS =
            listOf(
                EXTRA_SESSION_ID,
                // internal const EXTRA_WEB_APP_MANIFEST in feature-pwa ext/Bundle.kt
                "mozilla.components.feature.pwa.EXTRA_WEB_APP_MANIFEST",
                // internal const EXTRA_URL_OVERRIDE in feature-pwa ext/Intent.kt: loads any URL into an installed web
                // app; only WebAppInterceptor sets it, and the app does not use that.
                "mozilla.components.feature.pwa.EXTRA_URL_OVERRIDE",
                NotificationManager.EXTRA_WEB_NOTIFICATION,
                // Added to the load flags unchecked: it could load a page without Safe Browsing, tracking protection or
                // the request interceptor. Nothing in the app sets it.
                TabIntentProcessor.EXTRA_APP_LINK_LAUNCH_TYPE,
            )
    }
}
