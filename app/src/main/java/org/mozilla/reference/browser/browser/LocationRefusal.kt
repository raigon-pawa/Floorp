/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION

private val LOCATION = setOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)

/** Whether these results of an Android permission prompt refuse location: neither precise nor approximate. */
internal fun isLocationRefused(results: Map<String, Boolean>) =
    results.keys.any { it in LOCATION } && LOCATION.none { results[it] == true }

/**
 * GeckoView stores a refused Android location prompt as a permanent block of the site's location, also right after the
 * user chose "Allow" and "Remember": the site could never ask again, not even once the app may use location. This sets
 * it back to "ask". Call it after the refusal was passed on: GeckoView stores the block as it handles the refusal, and
 * handles this query after it.
 */
internal fun forgetLocationBlock(runtime: GeckoRuntime, url: String, private: Boolean) {
    val storage = runtime.storageController
    storage.getPermissions(url, private).accept { permissions ->
        permissions
            ?.filter { it.permission == PERMISSION_GEOLOCATION && it.value == VALUE_DENY }
            ?.forEach { storage.setPermission(it, VALUE_PROMPT) }
    }
}
