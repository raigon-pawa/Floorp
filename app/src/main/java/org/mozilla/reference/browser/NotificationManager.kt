/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager as AndroidNotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.findTabOrCustomTabOrSelectedTab
import mozilla.components.concept.engine.permission.SitePermissions
import mozilla.components.concept.sync.Device
import mozilla.components.concept.sync.TabData
import mozilla.components.feature.intent.ext.getSessionId
import mozilla.components.feature.sitepermissions.NotificationPermissionDialogFragment
import mozilla.components.feature.webnotifications.WebNotificationIntentProcessor
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.base.ids.SharedIdsHelper
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.kotlin.getOrigin
import mozilla.components.support.ktx.kotlin.isSameOriginAs
import mozilla.components.support.ktx.kotlin.stripDefaultPort
import mozilla.components.support.utils.SafeIntent
import mozilla.components.support.utils.ext.getParcelableExtraCompat
import org.mozilla.geckoview.WebNotification
import org.mozilla.reference.browser.ext.components

/** Manages notification channels and allows displaying different supported types of notifications. */
object NotificationManager {
    // Send Tab
    private const val RECEIVE_TABS_TAG = "org.mozilla.reference.browser.receivedTabs"
    private const val RECEIVE_TABS_CHANNEL_ID = "org.mozilla.reference.browser.ReceivedTabsChannel"

    // Default
    private const val NOTIFICATION_CHANNEL_ID = "default-notification-channel"

    /** Extra of a web notification's tap intent (internal const EXTRA_ON_CLICK in feature-webnotifications). */
    const val EXTRA_WEB_NOTIFICATION = "mozac.feature.webnotifications.generic.onclick"

    private val logger = Logger("NotificationManager")

    /**
     * Once a site may notify, Android Components offers the system notification settings when the app may not post
     * notifications. This asks Android first instead, and keeps that offer only when Android no longer shows its
     * prompt. Register it on the activity's fragment manager.
     */
    fun askBeforeOfferingSettings(activity: AppCompatActivity, delegate: NotificationsDelegate) =
        object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                if (f !is NotificationPermissionDialogFragment || SDK_INT < Build.VERSION_CODES.TIRAMISU) return
                // The app-wide delegate keeps these callbacks after the screen is gone, and the answer can arrive after
                // the activity was recreated: hold the screen weakly and dismiss only a dialog that is still shown.
                val dialog = WeakReference(f)
                val host = WeakReference(activity)
                val dismiss = { dialog.get()?.takeIf { it.isAdded }?.dismissAllowingStateLoss() }
                // The site that was just allowed: the custom tab of this window, or the selected tab.
                val tab =
                    activity.components.core.store.state.findTabOrCustomTabOrSelectedTab(
                        SafeIntent(activity.intent).getSessionId()
                    )
                val origin = tab?.content?.url?.getOrigin()?.let { it to tab.content.private }
                delegate.requestNotificationPermission(
                    onPermissionGranted = { dismiss() },
                    onPermissionRejected = {
                        // True right after a "no" in the prompt; false when Android refused without asking, and the
                        // offer of the settings stays. Either way the app cannot post now, so the site keeps no grant
                        // (it asks again once the app may notify), also when the offer is cancelled.
                        if (host.get()?.shouldShowRequestPermissionRationale(POST_NOTIFICATIONS) == true) dismiss()
                        forgetNotificationGrant(activity.components, origin)
                    },
                )
            }
        }

    /**
     * The site was allowed to notify, then Android's notification permission refused: notifications would be dropped
     * silently, and start once the app may post them for some other reason. Do not keep "Always allow" for the site, so
     * it asks again.
     */
    private fun forgetNotificationGrant(components: Components, origin: Pair<String, Boolean>?) {
        val (url, private) = origin ?: return
        val storage = components.core.geckoSitePermissionsStorage
        components.applicationScope.launch(Dispatchers.Main) {
            val permissions = storage.findSitePermissionsBy(url, includeTemporary = false, private = private)
            if (permissions?.notification == SitePermissions.Status.ALLOWED) {
                storage.update(permissions.copy(notification = SitePermissions.Status.NO_DECISION), private)
            }
        }
    }

    /**
     * Passes a tap on a web notification to the page and brings its site forward: the tab or custom tab that shows it,
     * else a tab of the site, else a new tab. Only call it from [BrowserActivity], which is not exported: the
     * notification can then only come from this app's own PendingIntent.
     */
    fun onWebNotificationTapped(activity: Activity, intent: Intent) {
        val components = activity.components
        if (!WebNotificationIntentProcessor(components.core.engine).process(intent)) return
        val notification = intent.getParcelableExtraCompat(EXTRA_WEB_NOTIFICATION, WebNotification::class.java)
        // Null for notifications of extensions.
        val source = notification?.source ?: return
        val appContext = activity.applicationContext
        // Right after a cold start the tabs are still being restored.
        components.applicationScope.launch(Dispatchers.Main) {
            val state = components.core.store.flow().first { it.restoreComplete }
            val sameSite =
                state.tabs.filter {
                    it.content.private == notification.privateBrowsing && it.content.url.isSameOriginAs(source)
                }
            // The page that showed it, when it is still open: in a tab, or in a custom tab of its own task.
            val shownIn = sameSite.firstOrNull { it.content.url == source }
            val customTab =
                state.customTabs.firstOrNull {
                    it.content.private == notification.privateBrowsing && it.content.url == source
                }
            if (
                shownIn == null &&
                    customTab != null &&
                    ExternalAppBrowserActivity.bringToFront(appContext, customTab.id)
            ) {
                return@launch
            }
            val tab = shownIn ?: sameSite.firstOrNull()
            val tabsUseCases = components.useCases.tabsUseCases
            if (tab != null) {
                tabsUseCases.selectTab(tab.id)
            } else {
                // The source of a service worker's notification is its script, so open the site's start instead.
                tabsUseCases.addTab(source.stripDefaultPort(), private = notification.privateBrowsing)
            }
        }
    }

    /**
     * Displays a notification for each valid tab received from a device.
     *
     * @param context The Android application context.
     * @param device The device from which the tabs were received, or `null` if unavailable.
     * @param tabs A list of tabs to be displayed.
     */
    @SuppressLint("MissingPermission", "NotifyUsage")
    fun showReceivedTabs(
        context: Context,
        device: Device?,
        tabs: List<TabData>,
    ) {
        // In the future, experiment with displaying multiple tabs from the same device as as Notification Groups.
        // For now, a single notification per tab received will suffice.
        logger.debug("Showing ${tabs.size} tab(s) received from deviceID=${device?.id}")

        tabs.forEach { tab ->
            val intent =
                Intent(Intent.ACTION_VIEW, tab.url.toUri()).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            val flags =
                if (SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                } else {
                    PendingIntent.FLAG_ONE_SHOT
                }
            val notificationId = SharedIdsHelper.getNextIdForTag(context, RECEIVE_TABS_TAG)
            val pendingIntent: PendingIntent =
                PendingIntent.getActivity(
                    context,
                    notificationId,
                    intent,
                    flags,
                )
            // We pick 'IMPORTANCE_HIGH' priority because this is a user-triggered action that is
            // expected to be part of a continuity flow. That is, user is expected to be waiting for
            // this notification on their device; make it obvious.
            val importance = AndroidNotificationManager.IMPORTANCE_HIGH
            val channelId =
                getNotificationChannelId(
                    context,
                    RECEIVE_TABS_CHANNEL_ID,
                    context.getString(R.string.fxa_received_tab_channel_name),
                    context.getString(R.string.fxa_received_tab_channel_description),
                    importance,
                )

            val builder =
                NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setSendTabTitle(context, device, tab)
                    .setWhen(System.currentTimeMillis())
                    .setContentText(tab.url)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_VIBRATE or Notification.DEFAULT_SOUND)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)

            NotificationManagerCompat.from(context)
                .notify(
                    RECEIVE_TABS_TAG,
                    notificationId,
                    builder.build(),
                )
        }
    }

    private fun getNotificationChannelId(
        context: Context,
        channelId: String = NOTIFICATION_CHANNEL_ID,
        channelName: String = context.resources.getString(R.string.default_notification_channel),
        description: String? = null,
        channelImportance: Int? = null,
    ): String {
        val importance = channelImportance ?: AndroidNotificationManager.IMPORTANCE_DEFAULT
        createNotificationChannelIfNeeded(
            context,
            channelId,
            channelName,
            description,
            importance,
        )

        return channelId
    }

    private fun createNotificationChannelIfNeeded(
        context: Context,
        channelId: String,
        channelName: String,
        channelDescription: String?,
        importance: Int = AndroidNotificationManager.IMPORTANCE_DEFAULT,
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager

        if (null != notificationManager.getNotificationChannel(channelId)) {
            return
        }

        val channel =
            NotificationChannel(
                    channelId,
                    channelName,
                    importance,
                )
                .apply {
                    description = channelDescription
                }

        notificationManager.createNotificationChannel(channel)
    }

    private fun NotificationCompat.Builder.setSendTabTitle(
        context: Context,
        device: Device?,
        tab: TabData,
    ): NotificationCompat.Builder {
        device?.let {
            setContentTitle(
                context.getString(
                    R.string.fxa_tab_received_from_notification_name,
                    it.displayName,
                )
            )
            return this
        }

        if (tab.title.isEmpty()) {
            setContentTitle(context.getString(R.string.fxa_tab_received_notification_name))
        } else {
            setContentTitle(tab.title)
        }
        return this
    }
}
