/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ui.robots

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewInteraction
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.uiautomator.UiSelector
import junit.framework.AssertionFailedError
import mozilla.components.browser.menu.R as menuR
import mozilla.components.browser.toolbar.R as toolbarR
import org.hamcrest.CoreMatchers.not
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.mozilla.reference.browser.helpers.TestAssetHelper.waitingTime
import org.mozilla.reference.browser.helpers.TestHelper.packageName
import org.mozilla.reference.browser.helpers.click

/** Implementation of Robot Pattern for three dot menu. Labels come from res/values/archwave_menu.xml. */
class ThreeDotMenuRobot {
    fun verifyThreeDotMenuExists() = threeDotMenuRecyclerViewExists()

    fun verifyBackButtonExists() = assertVisible(backButton())

    /** On the start page there is nothing to go back or forward to, or to share: the buttons show but do nothing. */
    fun verifyBackForwardAndShareDisabled() =
        listOf(backButton(), forwardButton(), shareButton()).forEach {
            it.check(matches(not(ViewMatchers.isEnabled())))
        }

    fun verifyForwardButtonExists() = assertForwardButton()

    fun verifyReloadButtonExists() = assertRefreshButton()

    fun verifyStopButtonExists() = assertStopButton()

    fun verifyShareButtonExists() = assertShareButton()

    fun verifyRequestDesktopSiteToggleExists() = assertRequestDesktopSiteToggle()

    fun verifyAddToHomescreenButtonExists() = assertAddToHomescreenButton()

    fun verifyFindInPageButtonExists() = assertFindInPageButton()

    fun verifyAddOnsButtonExists() = assertAddOnsButton()

    fun verifyOpenSettingsExists() = assertSettingsButton()

    fun verifyNewTabButtonExists() = assertVisible(newTabButton())

    fun verifyNewPrivateTabButtonExists() = assertVisible(newPrivateTabButton())

    fun verifyBackButtonDoesntExist() = backButton().check(ViewAssertions.doesNotExist())

    fun verifyShareButtonDoesntExist() = assertShareButtonDoesntExist()

    fun verifyRequestDesktopSiteToggleDoesntExist() = assertRequestDesktopSiteToggleDoesntExist()

    fun verifyFindInPageButtonDoesntExist() = assertFindInPageButtonDoesntExist()

    fun verifyForwardButtonDoesntExist() = assertForwardButtonDoesntExist()

    fun verifyReloadButtonDoesntExist() = assertRefreshButtonDoesntExist()

    fun verifyStopButtonDoesntExist() = assertStopButtonDoesntExist()

    fun verifyAddToHomescreenButtonDoesntExist() = assertAddToHomescreenButtonDoesntExist()

    fun verifyRequestDesktopSiteIsTurnedOff() = assertRequestDesktopSiteIsTurnedOff()

    fun verifyRequestDesktopSiteIsTurnedOn() = assertRequestDesktopSiteIsTurnedOn()

    class Transition {
        fun openNewTab(interact: NavigationToolbarRobot.() -> Unit): NavigationToolbarRobot.Transition {
            newTabButton().click()
            mDevice.waitForIdle()
            NavigationToolbarRobot().interact()
            return NavigationToolbarRobot.Transition()
        }

        fun openNewPrivateTab(interact: NavigationToolbarRobot.() -> Unit): NavigationToolbarRobot.Transition {
            newPrivateTabButton().click()
            mDevice.waitForIdle()
            NavigationToolbarRobot().interact()
            return NavigationToolbarRobot.Transition()
        }

        fun goForward(interact: BrowserRobot.() -> Unit): BrowserRobot.Transition {
            forwardButton().click()
            mDevice
                .findObject(UiSelector().resourceId("$packageName:id/mozac_browser_toolbar_progress"))
                .waitUntilGone(waitingTime)
            mDevice.waitForIdle()

            BrowserRobot().interact()
            return BrowserRobot.Transition()
        }

        fun refreshPage(interact: BrowserRobot.() -> Unit): BrowserRobot.Transition {
            refreshButton().click()
            BrowserRobot().interact()
            return BrowserRobot.Transition()
        }

        fun doStop(interact: NavigationToolbarRobot.() -> Unit): NavigationToolbarRobot.Transition {
            stopButton().click()
            NavigationToolbarRobot().interact()
            return NavigationToolbarRobot.Transition()
        }

        fun openShare(interact: ContentPanelRobot.() -> Unit): ContentPanelRobot.Transition {
            shareButton().click()
            mDevice.waitForIdle()
            ContentPanelRobot().interact()
            return ContentPanelRobot.Transition()
        }

        fun switchRequestDesktopSiteToggle(
            interact: NavigationToolbarRobot.() -> Unit
        ): NavigationToolbarRobot.Transition {
            try {
                mDevice.findObject(UiSelector().textContains("Desktop site")).waitForExists(waitingTime)
                requestDesktopSiteToggle().click()
                mDevice.waitForIdle()
                assertTrue(
                    mDevice
                        .findObject(UiSelector().resourceId("$packageName:id/mozac_browser_menu_recyclerView"))
                        .waitUntilGone(waitingTime)
                )
            } catch (e: AssertionFailedError) {
                println("Failed to click request desktop toggle")
                // If the click didn't succeed the main menu remains displayed and should be dismissed
                mDevice.pressBack()
                threeDotMenuButton().click()
                mDevice.findObject(UiSelector().textContains("Desktop site")).waitForExists(waitingTime)
                // Click again the Desktop site toggle
                requestDesktopSiteToggle().click()
                mDevice.waitForIdle()
                assertTrue(
                    mDevice
                        .findObject(UiSelector().resourceId("$packageName:id/mozac_browser_menu_recyclerView"))
                        .waitUntilGone(waitingTime)
                )
            }
            NavigationToolbarRobot().interact()
            return NavigationToolbarRobot.Transition()
        }

        fun openFindInPage(interact: FindInPagePanelRobot.() -> Unit): FindInPagePanelRobot.Transition {
            findInPageButton().click()
            FindInPagePanelRobot().interact()
            return FindInPagePanelRobot.Transition()
        }

        fun openSettings(interact: SettingsViewRobot.() -> Unit): SettingsViewRobot.Transition {
            settingsButton().click()
            mDevice.waitForIdle()
            SettingsViewRobot().interact()
            return SettingsViewRobot.Transition()
        }

        fun openAddonsManager(interact: AddonsManagerRobot.() -> Unit): AddonsManagerRobot.Transition {
            addOnsButton().click()
            // Once an extension with an action is installed, Extensions opens a submenu that ends in the manager.
            val manage = mDevice.findObject(UiSelector().text("Manage extensions"))
            if (manage.waitForExists(SUBMENU_WAIT_MS)) manage.click()

            AddonsManagerRobot().interact()
            return AddonsManagerRobot.Transition()
        }

        fun goBack(interact: NavigationToolbarRobot.() -> Unit): NavigationToolbarRobot.Transition {
            mDevice.pressBack()

            NavigationToolbarRobot().interact()
            return NavigationToolbarRobot.Transition()
        }

        fun openAddToHomeScreen(interact: AddToHomeScreenRobot.() -> Unit): AddToHomeScreenRobot.Transition {
            mDevice.findObject(UiSelector().text("Add to Home screen")).waitForExists(waitingTime)
            addToHomescreenButton().click()

            AddToHomeScreenRobot().interact()
            return AddToHomeScreenRobot.Transition()
        }
    }
}

private const val SUBMENU_WAIT_MS = 2000L

private fun threeDotMenuRecyclerViewExists() {
    onView(withId(menuR.id.mozac_browser_menu_recyclerView)).check(matches(isDisplayed()))
}

private fun threeDotMenuButton() = onView(withId(toolbarR.id.mozac_browser_toolbar_menu))

private fun backButton() = onView(ViewMatchers.withContentDescription("Back"))

private fun newTabButton() = onView(ViewMatchers.withText("New tab"))

private fun newPrivateTabButton() = onView(ViewMatchers.withText("New private tab"))

private fun assertVisible(item: ViewInteraction) =
    item.check(matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun forwardButton() = onView(ViewMatchers.withContentDescription("Forward"))

private fun refreshButton() = onView(ViewMatchers.withContentDescription("Reload"))

private fun stopButton() = onView(ViewMatchers.withContentDescription("Stop"))

private fun shareButton() = onView(ViewMatchers.withContentDescription("Share"))

private fun requestDesktopSiteToggle() = onView(ViewMatchers.withText("Desktop site"))

private fun findInPageButton() = onView(ViewMatchers.withText("Find in page"))

private fun settingsButton() = onView(ViewMatchers.withText("Settings"))

private fun addToHomescreenButton() = onView(ViewMatchers.withText("Add to Home screen"))

private fun addOnsButton() = onView(ViewMatchers.withText("Extensions"))

private fun assertShareButtonDoesntExist() = shareButton().check(ViewAssertions.doesNotExist())

private fun assertRequestDesktopSiteToggleDoesntExist() =
    requestDesktopSiteToggle().check(ViewAssertions.doesNotExist())

private fun assertFindInPageButtonDoesntExist() = findInPageButton().check(ViewAssertions.doesNotExist())

private fun assertForwardButtonDoesntExist() = forwardButton().check(ViewAssertions.doesNotExist())

private fun assertRefreshButtonDoesntExist() = refreshButton().check(ViewAssertions.doesNotExist())

private fun assertStopButtonDoesntExist() = stopButton().check(ViewAssertions.doesNotExist())

private fun assertAddToHomescreenButtonDoesntExist() = addToHomescreenButton().check(ViewAssertions.doesNotExist())

private fun assertForwardButton() =
    forwardButton().check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertRefreshButton() =
    refreshButton().check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertStopButton() =
    stopButton().check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertShareButton() =
    shareButton().check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertRequestDesktopSiteToggle() =
    requestDesktopSiteToggle()
        .check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertAddToHomescreenButton() =
    addToHomescreenButton().check(matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertFindInPageButton() =
    findInPageButton()
        .check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertAddOnsButton() =
    addOnsButton().check(matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertSettingsButton() =
    settingsButton()
        .check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))

private fun assertRequestDesktopSiteIsTurnedOff() {
    assertFalse(mDevice.findObject(UiSelector().textContains("Desktop site")).isChecked)
}

private fun assertRequestDesktopSiteIsTurnedOn() {
    assertTrue(mDevice.findObject(UiSelector().textContains("Desktop site")).isChecked)
}
