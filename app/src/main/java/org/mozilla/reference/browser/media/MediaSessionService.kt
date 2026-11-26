/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.media

import android.content.Intent
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.base.crash.CrashReporting
import mozilla.components.feature.media.service.AbstractMediaSessionService
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.reference.browser.ext.components

/** [AbstractMediaSessionService] implementation for injecting [BrowserStore] singleton. */
class MediaSessionService : AbstractMediaSessionService() {
    override val store: BrowserStore by lazy { components.core.store }
    override val crashReporter: CrashReporting by lazy { components.analytics.crashReporter }
    override val notificationsDelegate: NotificationsDelegate by lazy { components.notificationsDelegate }

    override fun onCreate() {
        super.onCreate()
        // The media feature only binds this service, and Android tells only started services that the app was swiped
        // away from Recents: the library's onTaskRemoved, which stops the tabs' media and the notification, never ran
        // and the page kept playing. Started as well, it hears of it; the library's shutdown stops it again.
        try {
            startService(Intent(this, MediaSessionService::class.java))
        } catch (e: IllegalStateException) {
            // Playback that starts while the app is in the background: no swipe from Recents to hear of then.
            Logger.warn("Could not start the media service", e)
        }
    }
}
