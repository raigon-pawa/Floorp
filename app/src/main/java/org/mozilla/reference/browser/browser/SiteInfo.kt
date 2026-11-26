/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.content.Context
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.SessionState
import mozilla.components.concept.engine.permission.SitePermissions
import mozilla.components.feature.session.TrackingProtectionUseCases
import mozilla.components.support.ktx.kotlin.getOrigin
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.ext.components

/** The decisions remembered in [permissions] (not the ones still to be asked), as label and whether it is allowed. */
fun rememberedPermissions(permissions: SitePermissions?): List<Pair<Int, Boolean>> {
    if (permissions == null) return emptyList()
    return listOf(
            R.string.archwave_site_info_location to permissions.location,
            R.string.archwave_site_info_notification to permissions.notification,
            R.string.archwave_site_info_camera to permissions.camera,
            R.string.archwave_site_info_microphone to permissions.microphone,
            R.string.archwave_site_info_storage to permissions.localStorage,
            R.string.archwave_site_info_drm to permissions.mediaKeySystemAccess,
            R.string.archwave_site_info_cross_site_cookies to permissions.crossOriginStorageAccess,
            R.string.archwave_site_info_local_device to permissions.localDeviceAccess,
            R.string.archwave_site_info_local_network to permissions.localNetworkAccess,
        )
        .filter { (_, status) -> status != SitePermissions.Status.NO_DECISION }
        .map { (label, status) -> label to status.isAllowed() }
}

/**
 * Shows the site information panel for the web page in [tab], which the address bar's shield and lock open: whether the
 * connection is secure, the trackers blocked with a switch that makes the site a tracking-protection exception, and the
 * permissions remembered for the site with a way to reset them.
 */
fun showSiteInfo(context: Context, scope: CoroutineScope, tab: SessionState) {
    val url = tab.content.url
    val host = url.toUri().host ?: return
    val origin = url.getOrigin() ?: return
    val components = context.components
    val storage = components.core.geckoSitePermissionsStorage
    val private = tab.content.private
    scope.launch {
        val permissions = storage.findSitePermissionsBy(origin, includeTemporary = false, private = private)
        val remembered = rememberedPermissions(permissions)
        val view = LayoutInflater.from(context).inflate(R.layout.archwave_site_info, null)

        val security = tab.content.securityInfo
        view
            .findViewById<TextView>(R.id.site_info_security)
            .setText(
                if (security.isSecure) R.string.archwave_site_info_secure else R.string.archwave_site_info_insecure
            )
        view.findViewById<TextView>(R.id.site_info_issuer).apply {
            isVisible = security.isSecure && security.issuer.isNotBlank()
            text = context.getString(R.string.archwave_site_info_issuer, security.issuer)
        }

        val protection = tab.trackingProtection
        val exception = protection.ignoredOnTrackingProtection
        val tracking = view.findViewById<SwitchCompat>(R.id.site_info_tracking)
        tracking.isVisible = protection.enabled || exception
        tracking.isChecked = !exception
        view.findViewById<TextView>(R.id.site_info_trackers).text =
            when {
                exception -> context.getString(R.string.archwave_site_info_tracking_off_for_site)
                !protection.enabled -> context.getString(R.string.archwave_site_info_tracking_off)
                else ->
                    protection.blockedTrackers.size.let {
                        context.resources.getQuantityString(R.plurals.archwave_site_info_trackers_blocked, it, it)
                    }
            }

        view.findViewById<TextView>(R.id.site_info_permissions).text =
            if (remembered.isEmpty()) {
                context.getString(R.string.archwave_site_info_no_permissions)
            } else {
                remembered.joinToString("\n") { (label, allowed) ->
                    context.getString(
                        R.string.archwave_site_info_permission,
                        context.getString(label),
                        context.getString(
                            if (allowed) R.string.archwave_site_info_allowed else R.string.archwave_site_info_blocked
                        ),
                    )
                }
            }

        val dialog =
            AlertDialog.Builder(context)
                .setTitle(host)
                .setView(view)
                .setPositiveButton(R.string.archwave_site_info_done, null)
                .apply {
                    if (permissions != null && remembered.isNotEmpty()) {
                        setNeutralButton(R.string.archwave_site_info_reset_permissions) { _, _ ->
                            scope.launch {
                                storage.remove(permissions, private)
                                Toast.makeText(
                                        context,
                                        R.string.archwave_site_info_permissions_reset,
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            }
                        }
                    }
                }
                .create()

        tracking.setOnCheckedChangeListener { _, protect ->
            val useCases = TrackingProtectionUseCases(components.core.store, components.core.engine)
            if (protect) useCases.removeException(tab.id) else useCases.addException(tab.id)
            // The page's trackers were already blocked or loaded; the change applies from the next load.
            components.useCases.sessionUseCases.reload(tab.id)
            dialog.dismiss()
        }

        // The page itself is kept out of screenshots in a private tab; so is what the panel says about it.
        if (private) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }
}
