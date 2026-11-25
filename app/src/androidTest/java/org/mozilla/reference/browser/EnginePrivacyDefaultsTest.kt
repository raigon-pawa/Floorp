/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import androidx.test.platform.app.InstrumentationRegistry
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy
import mozilla.components.concept.engine.mediaquery.PreferredColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.helpers.BrowserActivityTestRule

/**
 * The privacy defaults (decision 7) as the running engine has them on a fresh install. PrivacyDefaultsTest checks the
 * preference resources and the mapping helpers; this checks they reach the engine (each test starts with cleared data).
 */
class EnginePrivacyDefaultsTest {
    @get:Rule val activityTestRule = BrowserActivityTestRule()

    @Test
    fun engineStartsWithThePrivacyDefaults() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val settings = instrumentation.targetContext.components.core.engine.settings
            assertEquals(TrackingProtectionPolicy.strict(), settings.trackingProtectionPolicy)
            assertEquals(Engine.HttpsOnlyMode.ENABLED, settings.httpsOnlyMode)
            assertEquals(Engine.DohSettingsMode.INCREASED, settings.dohSettingsMode)
            assertEquals("https://dns.quad9.net/dns-query", settings.dohProviderUrl)
            assertTrue("Global Privacy Control", settings.globalPrivacyControlEnabled)
            assertTrue("query parameter stripping", settings.queryParameterStripping)
            assertTrue("query parameter stripping in private tabs", settings.queryParameterStrippingPrivateBrowsing)
            assertEquals(PreferredColorScheme.Dark, settings.preferredColorScheme)
        }
    }
}
