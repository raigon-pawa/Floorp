/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import mozilla.components.browser.state.selector.findCustomTab
import mozilla.components.browser.state.state.ExternalAppType
import mozilla.components.feature.intent.ext.getSessionId
import mozilla.components.support.utils.SafeIntent
import org.mozilla.reference.browser.browser.ExternalAppBrowserFragment
import org.mozilla.reference.browser.ext.components

/**
 * Activity that holds the BrowserFragment that is launched within an external app, such as custom tabs and progressive
 * web apps.
 */
class ExternalAppBrowserActivity : BrowserActivity() {
    override val showsAppNotices = false

    private val customTabId: String?
        get() = SafeIntent(intent).getSessionId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        customTabId?.let { tasks[it] = taskId }
    }

    override fun onDestroy() {
        super.onDestroy()
        customTabId?.let { tasks.remove(it, taskId) }
    }

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

    /** Finds the window of a custom tab again. */
    companion object {
        // The task of each custom tab's window, by custom tab id. Only touched on the main thread.
        private val tasks = mutableMapOf<String, Int>()

        /** Brings the window of the custom tab [customTabId] to the front; false when it has none. */
        fun bringToFront(context: Context, customTabId: String): Boolean {
            val taskId = tasks[customTabId] ?: return false
            val task =
                context.getSystemService<ActivityManager>()?.appTasks?.firstOrNull {
                    it.taskInfo?.let { info -> info.taskIdCompat() == taskId } == true
                } ?: return false
            task.moveToFront()
            return true
        }

        @Suppress("DEPRECATION") // RecentTaskInfo.id is the running task's id, as taskId is from Android 10.
        private fun ActivityManager.RecentTaskInfo.taskIdCompat() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) taskId else id
    }
}
