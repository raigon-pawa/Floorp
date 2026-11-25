/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import androidx.fragment.app.Fragment
import mozilla.components.browser.state.selector.findCustomTab
import mozilla.components.browser.state.state.ExternalAppType
import org.mozilla.reference.browser.browser.ExternalAppBrowserFragment
import org.mozilla.reference.browser.ext.components

/**
 * Activity that holds the BrowserFragment that is launched within an external app, such as custom tabs and progressive
 * web apps.
 */
class ExternalAppBrowserActivity : BrowserActivity() {
    override fun createBrowserFragment(sessionId: String?): Fragment =
        if (sessionId != null) {
            // From the tab the web app intent processor made, never from the intent: another app could hide the toolbar
            // with a manifest of its own. A plain custom tab can learn its page's manifest too, but is no web app.
            val tab = components.core.store.state.findCustomTab(sessionId)
            val manifest =
                tab?.content?.webAppManifest?.takeIf {
                    tab.config.externalAppType == ExternalAppType.PROGRESSIVE_WEB_APP
                }
            ExternalAppBrowserFragment.create(sessionId, manifest)
        } else {
            // Fall back to browser fragment
            super.createBrowserFragment(sessionId)
        }
}
