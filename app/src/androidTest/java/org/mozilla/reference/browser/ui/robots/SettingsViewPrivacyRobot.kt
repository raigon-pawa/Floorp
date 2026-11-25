/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ui.robots

import androidx.annotation.StringRes
import androidx.test.espresso.Espresso
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.hamcrest.CoreMatchers.allOf
import org.junit.Assert.assertNotNull
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.helpers.TestAssetHelper
import org.mozilla.reference.browser.helpers.hasCousin

/** Implementation of Robot Pattern for the settings privacy menu. */
class SettingsViewPrivacyRobot {
    fun verifyPrivacyUpButton() = assertPrivacyUpButton()

    fun verifyPrivacySettings() = assertPrivacySettingsView()

    fun verifyTrackingProtectionHeading() = assertTrackingProtectionHeading()

    fun verifyTPEnableInNormalBrowsing() = assertTpEnableInNormalBrowsing()

    fun verifyTPEnableinPrivateBrowsing() = assertTpEnableInPrivateBrowsing()

    /** Asserts that the switch in the row titled [title] is shown and in the [checked] state. */
    fun verifySwitch(
        @StringRes title: Int,
        checked: Boolean,
    ) =
        privacySwitch(title)
            .check(
                ViewAssertions.matches(allOf(ViewMatchers.isDisplayed(), if (checked) isChecked() else isNotChecked()))
            )

    /** Asserts that the DNS over HTTPS row is shown with Quad9, the default resolver, as its summary. */
    fun verifyDohDefault() {
        Espresso.onView(withText(R.string.preferences_doh)).check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(withText("Quad9 (default)")).check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    class Transition {
        fun settingsViewPrivacy(): SettingsViewPrivacyRobot.Transition = SettingsViewPrivacyRobot.Transition()
    }
}

private fun privacySwitch(@StringRes title: Int) =
    Espresso.onView(allOf(withId(androidx.preference.R.id.switchWidget), hasCousin(withText(title))))

private fun privacySettingsView() = Espresso.onView(ViewMatchers.withText("Privacy"))

private fun trackingProtectionHeading() = Espresso.onView(ViewMatchers.withText("Tracking Protection"))

private fun tpEnableInNormalBrowsing() = Espresso.onView(ViewMatchers.withText("Enable in Normal Browsing Mode"))

private fun tpEnableInPrivateBrowsing() = Espresso.onView(ViewMatchers.withText("Enable in Private Browsing Mode"))

// "Navigate up" is the button's content description, not text.
private fun assertPrivacyUpButton() {
    assertNotNull(mDevice.wait(Until.findObject(By.desc("Navigate up")), TestAssetHelper.waitingTimeShort))
}

private fun assertPrivacySettingsView() =
    privacySettingsView()
        .check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertTrackingProtectionHeading() =
    trackingProtectionHeading()
        .check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertTpEnableInNormalBrowsing() =
    tpEnableInNormalBrowsing()
        .check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertTpEnableInPrivateBrowsing() =
    tpEnableInPrivateBrowsing()
        .check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))
