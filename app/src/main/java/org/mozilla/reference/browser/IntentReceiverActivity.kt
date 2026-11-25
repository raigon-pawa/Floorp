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
import androidx.core.net.toUri
import mozilla.components.feature.intent.ext.EXTRA_SESSION_ID
import mozilla.components.feature.intent.ext.sanitize
import mozilla.components.feature.media.service.AbstractMediaSessionService
import mozilla.components.support.ktx.kotlin.isUrl
import mozilla.components.support.utils.WebURLFinder
import org.mozilla.reference.browser.ext.components

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

        // Explicitly remove the new task and clear task flags (Our browser activity is a single
        // task activity and we never want to start a second task here).
        intent.flags = intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
        intent.flags = intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK.inv()

        // LauncherActivity is started with the "excludeFromRecents" flag (set in manifest). We
        // do not want to propagate this flag from the launcher activity to the browser.
        intent.flags = intent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS.inv()

        val utils = components.utils

        val processor = utils.intentProcessors.firstOrNull { it.process(intent) }

        val className =
            if (processor in utils.externalIntentProcessors) {
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
        intent.flags = intent.flags and GRANT_FLAGS.inv()

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
            )

        const val GRANT_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
