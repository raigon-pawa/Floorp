/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.icons.Icon
import mozilla.components.browser.icons.IconRequest
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.permission.SitePermissionsStorage
import mozilla.components.concept.engine.webnotifications.WebNotification
import mozilla.components.concept.engine.webnotifications.WebNotificationDelegate
import mozilla.components.feature.webnotifications.R as WebNotificationsR
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.base.ids.SharedIdsHelper
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.kotlin.getOrigin
import mozilla.components.support.ktx.kotlin.tryGetHostFromUrl

/**
 * Shows web notifications, as Android Components' WebNotificationFeature (160) does, but in a group of their own with a
 * summary. Without one Android grouped two or more of them itself, and a tap on that stack opened the launcher entry:
 * the page got no click and every notification in it was gone. Each tap now reaches its own notification's page. Taps
 * go to [BrowserActivity], which passes them on (NotificationManager.onWebNotificationTapped).
 */
class WebNotificationsFeature(
    private val context: Context,
    engine: Engine,
    private val icons: BrowserIcons,
    @param:DrawableRes private val smallIcon: Int,
    private val sitePermissionsStorage: SitePermissionsStorage,
    private val notificationsDelegate: NotificationsDelegate,
) : WebNotificationDelegate {
    private val logger = Logger("WebNotificationsFeature")

    init {
        try {
            engine.registerWebNotificationDelegate(this)
        } catch (e: UnsupportedOperationException) {
            logger.error("failed to register for web notification delegate", e)
        }
    }

    override fun onShowNotification(webNotification: WebNotification): Deferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        CoroutineScope(Dispatchers.IO).launch {
            // Extensions' notifications were allowed with their permissions at install time.
            if (!webNotification.triggeredByWebExtension) {
                val origin = webNotification.sourceUrl?.getOrigin() ?: return@launch
                val permissions =
                    sitePermissionsStorage.findSitePermissionsBy(origin, private = webNotification.privateBrowsing)
                if (permissions?.notification?.isAllowed() != true) return@launch
            }
            ensureChannelExists()
            val manager = notificationsDelegate.notificationManagerCompat
            manager.cancel(webNotification.tag, NOTIFICATION_ID)
            notificationsDelegate.notify(
                webNotification.tag,
                NOTIFICATION_ID,
                build(webNotification),
                onPermissionGranted = {
                    notificationsDelegate.notify(notificationId = SUMMARY_ID, notification = summary())
                    deferred.complete(true)
                },
                onPermissionRejected = { deferred.complete(false) },
            )
        }
        return deferred
    }

    override fun onCloseNotification(webNotification: WebNotification) {
        val manager = notificationsDelegate.notificationManagerCompat
        manager.cancel(webNotification.tag, NOTIFICATION_ID)
        // The page closed the last one: the group's row would stay behind, empty.
        val others = manager.activeNotifications.any { it.notification.group == GROUP && it.id != SUMMARY_ID }
        if (!others) manager.cancel(SUMMARY_ID)
    }

    private fun ensureChannelExists() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(WebNotificationsR.string.mozac_feature_notification_channel_name),
                android.app.NotificationManager.IMPORTANCE_DEFAULT,
            )
        channel.setShowBadge(true)
        channel.lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        notificationsDelegate.notificationManagerCompat.createNotificationChannel(channel)
    }

    // The group's collapsed row. A tap there opens the browser and keeps the notifications: each has its page's click,
    // which only a tap on it (once the group is open) should give.
    private fun summary(): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    SUMMARY_ID,
                    Intent(context, BrowserActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setSilent(true)
            .build()

    // As the library's NativeNotificationBridge (internal), plus the group.
    private suspend fun build(notification: WebNotification): Notification {
        val requestId = SharedIdsHelper.getNextIdForTag(context, PENDING_INTENT_TAG)
        fun tapIntent(action: String?) =
            PendingIntent.getActivity(
                context,
                requestId,
                Intent(context, BrowserActivity::class.java)
                    .putExtra(NotificationManager.EXTRA_WEB_NOTIFICATION, notification.engineNotification)
                    .setAction(action),
                PendingIntent.FLAG_IMMUTABLE,
            )

        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(smallIcon)
                .setContentTitle(notification.title)
                .setShowWhen(true)
                .setWhen(notification.timestamp)
                .setAutoCancel(true)
                .setSilent(notification.silent)
                .setGroup(GROUP)
                .setContentIntent(tapIntent(null))
        notification.actions.forEach { builder.addAction(0, it.title, tapIntent(it.name)) }
        notification.sourceUrl?.let { builder.setSubText(it.tryGetHostFromUrl()) }
        notification.body?.let { builder.setContentText(it).setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        loadIcon(notification)?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    private suspend fun loadIcon(notification: WebNotification): Bitmap? {
        val url = notification.sourceUrl ?: return null
        val iconUrl = notification.iconUrl ?: return null
        val resource = IconRequest.Resource(url = iconUrl, type = IconRequest.Resource.Type.MANIFEST_ICON)
        val request =
            IconRequest(url = url, size = IconRequest.Size.DEFAULT, resources = listOf(resource), isPrivate = true)
        return icons.loadIcon(request).await().takeIf { it.source != Icon.Source.GENERATOR }?.bitmap
    }

    private companion object {
        // The library's, so the channel the user may have set up stays the same.
        const val CHANNEL_ID = "mozac.feature.webnotifications.generic.channel"
        const val PENDING_INTENT_TAG = "mozac.feature.webnotifications.generic.pendingintent"
        const val NOTIFICATION_ID = 1
        const val GROUP = "archwave.web_notifications"
        // Untagged, so it cannot clash with the notifications, which all carry a tag.
        const val SUMMARY_ID = 0x5757
    }
}
