/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import java.io.File
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy.TrackingCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.reference.browser.components.Core

/** Checks that each tracking protection switch only reaches its own kind of tab. */
class TrackingProtectionModesTest {
    @Test
    fun `normal only blocks content in normal tabs and nothing runtime-wide`() {
        val policy = Core.trackingProtectionPolicyFor(normalMode = true, privateMode = false)
        assertTrue(policy.useForRegularSessions)
        assertFalse(policy.useForPrivateSessions)
        assertTrue(policy.contains(TrackingCategory.SCRIPTS_AND_SUB_RESOURCES))
        assertTrue(policy.contains(TrackingCategory.SOCIAL))
        // Gecko applies strict social blocking to private tabs too.
        assertEquals(false, policy.strictSocialTrackingProtection)
    }

    @Test
    fun `both on keeps the strict preset`() {
        val policy = Core.trackingProtectionPolicyFor(normalMode = true, privateMode = true)
        assertTrue(policy.useForRegularSessions && policy.useForPrivateSessions)
        assertEquals(true, policy.strictSocialTrackingProtection)
    }

    @Test
    fun `private tabs follow the session flag, not Gecko's private-mode default`() {
        val config = File("src/main/assets/archwave-geckoview-config.yaml").readText()
        assertTrue(config.contains(Regex("""(?m)^\s+privacy\.trackingprotection\.pbmode\.enabled: false$""")))
    }
}
