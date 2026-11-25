/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ui.robots

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.anyOf
import org.hamcrest.CoreMatchers.not
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.helpers.TestAssetHelper
import org.mozilla.reference.browser.helpers.TestAssetHelper.waitingTime
import org.mozilla.reference.browser.helpers.TestHelper.getStringResource
import org.mozilla.reference.browser.helpers.TestHelper.packageName
import org.mozilla.reference.browser.helpers.click
import org.mozilla.reference.browser.helpers.hasCousin
import org.mozilla.reference.browser.settings.BrowsingDataCleaner

/** Implementation of Robot Pattern for the settings menu. */
class SettingsViewRobot {
    fun verifySettingsViewExists() = assertSettingsView()

    fun verifyNavigateUp() = assertNavigateUpButton()

    fun verifyPrivacyButton() = assertPrivacyButton()

    fun verifyPrivacySummary() = assertPrivacySummary()

    fun verifyOpenLinksInApps() = assertOpenLinksInApps()

    fun verifyMakeDefaultBrowserButton() = assertMakeDefaultBrowserButton()

    fun verifyAdvancedHeading() = assertAdvancedHeading()

    fun verifyRemoteDebugging() = assertRemoteDebugging()

    fun verifyCustomAddonCollectionButton() = assertCustomAddonCollectionButton()

    fun verifyAboutHeading() = assertAboutHeading()

    fun verifyAboutArchwaveButton() = assertAboutArchwaveButton()

    fun verifyGeneralHeading() = settingsRow(R.string.archwave_settings_general_category).check(matches(isVisible()))

    fun verifySearchEngineButton() = settingsRow(R.string.archwave_settings_search_engine).check(matches(isVisible()))

    fun verifyOpenLinksInPrivate() = openLinksInPrivateToggle().check(matches(isVisible()))

    fun verifyPrivacyAndSecurityHeading() =
        settingsRow(R.string.archwave_settings_privacy_category).check(matches(isVisible()))

    fun verifyClearBrowsingDataButton() = clearBrowsingDataButton().check(matches(isVisible()))

    /** Waits until the search engine row is enabled and shows [name], the engine in use. */
    fun verifySearchEngine(name: String) {
        assertTrue(
            mDevice.findObject(UiSelector().resourceId("android:id/summary").text(name)).waitForExists(waitingTime)
        )
        settingsRow(R.string.archwave_settings_search_engine).check(matches(isEnabled()))
    }

    /** Opens the search engine list and picks [name]. */
    fun selectSearchEngine(name: String) {
        settingsRow(R.string.archwave_settings_search_engine).click()
        onView(withText(name)).inRoot(isDialog()).click()
    }

    fun clickClearBrowsingData() = clearBrowsingDataButton().click()

    /** Asserts the row titled [label] in the clear-browsing-data dialog is [checked]. */
    fun verifyClearDataItem(
        @StringRes label: Int,
        checked: Boolean,
    ) = onView(withText(label)).inRoot(isDialog()).check(matches(if (checked) isChecked() else isNotChecked()))

    fun toggleClearDataItem(@StringRes label: Int) = onView(withText(label)).inRoot(isDialog()).click()

    fun verifyClearButtonEnabled(enabled: Boolean) =
        clearButton().check(matches(if (enabled) isEnabled() else not(isEnabled())))

    /** Confirms the dialog and waits until the clearing has finished. */
    fun confirmClearData() {
        clearButton().click()
        val deadline = SystemClock.uptimeMillis() + waitingTime
        while (BrowsingDataCleaner.running && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(POLL_MS)
        assertFalse("Clearing did not finish", BrowsingDataCleaner.running)
    }

    fun verifySettingsRecyclerViewToExist() = waitForSettingsRecyclerViewToExist()

    fun clickCustomAddonCollectionButton() = customAddonCollectionButton().click()

    fun verifyCustomAddonCollectionPanelExist() = assertCustomAddonCollectionPanel()

    fun clickOpenLinksInApps() = openLinksInAppsToggle().click()

    // toggleRemoteDebugging does not yet verify that the debug service is started
    // server runs on port 6000
    fun toggleRemoteDebuggingOn() {
        remoteDebuggingToggle().check(matches(isNotChecked()))
        remoteDebuggingToggle().click()
        remoteDebuggingToggle().check(matches(isChecked()))
    }

    fun toggleRemoteDebuggingOff() {
        remoteDebuggingToggle().check(matches(isChecked()))
        remoteDebuggingToggle().click()
        remoteDebuggingToggle().check(matches(isNotChecked()))
    }

    class Transition {
        fun openSettingsViewPrivacy(
            interact: SettingsViewPrivacyRobot.() -> Unit
        ): SettingsViewPrivacyRobot.Transition {
            privacyButton().click()
            SettingsViewPrivacyRobot().interact()
            return SettingsViewPrivacyRobot.Transition()
        }

        fun makeDefaultBrowser(interact: ExternalAppsRobot.() -> Unit): ExternalAppsRobot.Transition {
            makeDefaultBrowserButton().click()
            ExternalAppsRobot().interact()
            return ExternalAppsRobot.Transition()
        }

        fun openAboutArchwave(interact: BrowserRobot.() -> Unit): BrowserRobot.Transition {
            aboutArchwaveButton().click()
            BrowserRobot().interact()
            return BrowserRobot.Transition()
        }

        fun goBack(interact: NavigationToolbarRobot.() -> Unit): NavigationToolbarRobot.Transition {
            val navigateUpButton = mDevice.findObject(UiSelector().descriptionContains("Navigate up"))
            navigateUpButton.clickAndWaitForNewWindow()

            NavigationToolbarRobot().interact()
            return NavigationToolbarRobot.Transition()
        }
    }
}

private fun waitForSettingsRecyclerViewToExist() {
    mDevice.findObject(UiSelector().resourceId("$packageName:id/recycler_view")).waitForExists(waitingTime)
}

private fun assertSettingsView() {
    // verify that we are in the correct settings view
    assertTrue(
        mDevice
            .findObject(
                UiSelector()
                    .resourceId("$packageName:id/action_bar")
                    .childSelector(UiSelector().textContains(getStringResource(R.string.settings)))
            )
            .waitForExists(waitingTime)
    )
}

private fun privacyButton() = Espresso.onView(withText(R.string.privacy))

private fun privacySummary() = Espresso.onView(withText(R.string.preferences_privacy_summary))

private fun openLinksInAppsToggle() =
    Espresso.onView(
        allOf(
            withId(androidx.preference.R.id.switchWidget),
            hasCousin(withText(R.string.open_links_in_apps)),
        )
    )

private fun makeDefaultBrowserButton() = Espresso.onView(withText(R.string.preferences_make_default_browser))

private fun advancedHeading() = Espresso.onView(withText(R.string.developer_tools_category))

private fun remoteDebuggingToggle() =
    Espresso.onView(
        allOf(
            withId(androidx.preference.R.id.switchWidget),
            hasCousin(withText(R.string.preferences_remote_debugging)),
        )
    )

private fun customAddonCollectionButton() = onView(withText("Custom add-on collection"))

private fun aboutHeading() = Espresso.onView(withText(R.string.mozilla_category))

private fun aboutArchwaveButton() = Espresso.onView(withText(R.string.preferences_about_page))

private fun settingsRow(@StringRes title: Int) = onView(withText(title))

private fun isVisible() = withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)

private fun openLinksInPrivateToggle() =
    onView(
        allOf(
            withId(androidx.preference.R.id.switchWidget),
            hasCousin(withText(R.string.archwave_settings_open_links_in_private)),
        )
    )

private fun clearBrowsingDataButton() = settingsRow(R.string.archwave_settings_clear_data)

private fun clearButton() = onView(withText(R.string.archwave_clear_data_clear)).inRoot(isDialog())

private const val POLL_MS = 100L

// "Navigate up" is the button's content description, not text.
private fun assertNavigateUpButton() {
    assertNotNull(mDevice.wait(Until.findObject(By.desc("Navigate up")), TestAssetHelper.waitingTimeShort))
}

private fun assertPrivacyButton() =
    privacyButton().check(matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertPrivacySummary() =
    privacySummary().check(matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertOpenLinksInApps() =
    openLinksInAppsToggle().check(matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertMakeDefaultBrowserButton() =
    makeDefaultBrowserButton().check(matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertAdvancedHeading() =
    advancedHeading().check(matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertRemoteDebugging() =
    remoteDebuggingToggle().check(matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertCustomAddonCollectionButton() =
    customAddonCollectionButton().check(matches(withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertAboutHeading() =
    aboutHeading().check(matches(withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertAboutArchwaveButton() =
    aboutArchwaveButton().check(matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertCustomAddonCollectionPanel() {
    mDevice.waitForIdle()
    mDevice.findObject(UiSelector().resourceId("$packageName:id/parentPanel")).waitForExists(waitingTime)
    onView(
            allOf(
                withText(R.string.preferences_customize_amo_collection),
                anyOf(
                    withId(androidx.appcompat.R.id.alertTitle),
                    withId(android.R.id.title),
                ),
            )
        )
        .inRoot(isDialog())
        .check(matches(isCompletelyDisplayed()))
}
