/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import mozilla.components.concept.engine.permission.SitePermissions
import mozilla.components.concept.engine.permission.SitePermissions.Status.ALLOWED
import mozilla.components.concept.engine.permission.SitePermissions.Status.BLOCKED
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.reference.browser.R

class SiteInfoTest {
    @Test
    fun `only remembered decisions are listed`() {
        assertEquals(emptyList<Pair<Int, Boolean>>(), rememberedPermissions(null))
        assertEquals(emptyList<Pair<Int, Boolean>>(), rememberedPermissions(SitePermissions("https://a.org:443")))
        assertEquals(
            listOf(R.string.archwave_site_info_location to true, R.string.archwave_site_info_notification to false),
            rememberedPermissions(SitePermissions("https://a.org:443", location = ALLOWED, notification = BLOCKED)),
        )
    }
}
