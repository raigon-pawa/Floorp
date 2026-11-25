/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ui

import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.helpers.BrowserActivityTestRule
import org.mozilla.reference.browser.helpers.TestHelper
import org.mozilla.reference.browser.ui.robots.mDevice
import org.mozilla.reference.browser.ui.robots.navigationToolbar

/** The window may not be captured while private content is on screen: a private tab, or the list of private tabs. */
class PrivateWindowSecureTest {
    @get:Rule val activityTestRule = BrowserActivityTestRule()

    private val tabsUseCases
        get() = TestHelper.appContext.components.useCases.tabsUseCases

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            tabsUseCases.removeAllTabs()
            // A normal tab first: a tab added to an empty list is selected whatever selectTab says.
            tabsUseCases.addTab("about:blank", selectTab = true)
            tabsUseCases.addTab("about:blank", selectTab = false, private = true)
        }
        mDevice.waitForIdle()
    }

    // The flag follows the store, which settles shortly after the action: wait a moment for it.
    private fun assertSecure(expected: Boolean) {
        var secure = !expected
        repeat(WAIT_STEPS) {
            mDevice.waitForIdle()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                secure =
                    activityTestRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            }
            if (secure == expected) return
            Thread.sleep(WAIT_STEP_MS)
        }
        assertEquals(expected, secure)
    }

    @Test
    fun privateTabSelectedIsSecure() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            tabsUseCases.addTab("about:blank", selectTab = true, private = true)
        }
        assertSecure(true)
    }

    @Test
    fun privatePanelOverNormalTabIsSecure() {
        navigationToolbar {}.openTabTrayMenu { openPrivateBrowsing() }
        assertSecure(true)
    }

    @Test
    fun normalPanelOverNormalTabIsNotSecure() {
        navigationToolbar {}
            .openTabTrayMenu {
                openPrivateBrowsing()
                openRegularBrowsing()
            }
        assertSecure(false)
    }

    private companion object {
        const val WAIT_STEPS = 20
        const val WAIT_STEP_MS = 250L
    }
}
