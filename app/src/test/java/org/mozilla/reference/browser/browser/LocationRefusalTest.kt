/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.CAMERA
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which Android prompt results make the site's location go back to "ask". */
class LocationRefusalTest {
    @Test
    fun `refusing precise and approximate location is a refusal`() {
        assertTrue(isLocationRefused(mapOf(ACCESS_FINE_LOCATION to false, ACCESS_COARSE_LOCATION to false)))
        assertTrue(isLocationRefused(mapOf(ACCESS_FINE_LOCATION to false)))
    }

    @Test
    fun `approximate location only is not a refusal`() {
        assertFalse(isLocationRefused(mapOf(ACCESS_FINE_LOCATION to false, ACCESS_COARSE_LOCATION to true)))
        assertFalse(isLocationRefused(mapOf(ACCESS_FINE_LOCATION to true)))
    }

    @Test
    fun `other permissions are not about location`() {
        assertFalse(isLocationRefused(mapOf(CAMERA to false)))
    }
}
