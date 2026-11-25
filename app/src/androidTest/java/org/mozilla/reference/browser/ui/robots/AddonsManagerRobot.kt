/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ui.robots

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.hasSibling
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import mozilla.components.feature.addons.R as addonsR
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.containsString
import org.junit.Assert.assertTrue
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.ext.waitAndInteract
import org.mozilla.reference.browser.helpers.TestAssetHelper.waitingTime
import org.mozilla.reference.browser.helpers.TestAssetHelper.waitingTimeShort
import org.mozilla.reference.browser.helpers.TestHelper.appContext
import org.mozilla.reference.browser.helpers.TestHelper.assertUIObjectExists
import org.mozilla.reference.browser.helpers.TestHelper.getStringResource
import org.mozilla.reference.browser.helpers.TestHelper.itemWithResId
import org.mozilla.reference.browser.helpers.TestHelper.itemWithResIdContainingText
import org.mozilla.reference.browser.helpers.TestHelper.packageName
import org.mozilla.reference.browser.helpers.TestHelper.scrollToElementByText
import org.mozilla.reference.browser.helpers.click

/** Implementation of Robot Pattern for the addons manager. */
class AddonsManagerRobot {
    fun verifyAddonsRecommendedView() {
        assertTrue(mDevice.findObject(UiSelector().text("Recommended")).waitForExists(waitingTime))
        // Check uBlock is displayed in the "Recommended" section. Its summary comes from live AMO data and changes.
        assertUIObjectExists(itemWithResIdContainingText("$packageName:id/add_on_name", "uBlock Origin"))
        assertUIObjectExists(
            itemWithResId("$packageName:id/add_on_description"),
            itemWithResId("$packageName:id/rating"),
            itemWithResId("$packageName:id/review_count"),
            itemWithResId("$packageName:id/add_button"),
        )
    }

    fun verifyInstallAddonPrompt(addonName: String) = assertAddonPrompt(addonName)

    fun verifyAddonDownloadCompletedPrompt(addonName: String) = assertAddonDownloadCompletedPrompt(addonName)

    fun verifyAddonElementsView(addonName: String) = assertAddonElementsView(addonName)

    fun clickInstallAddonButton(addonName: String) = selectInstallAddonButton(addonName)

    fun clickCancelInstallButton() = cancelInstallButton()

    fun clickAllowInstallAddonButton() = allowInstallAddonButton()

    fun waitForAddonDownloadComplete() = waitForDownloadProgressUntilGone()

    fun openAddon(addonName: String) =
        itemWithResIdContainingText("$packageName:id/add_on_name", addonName).also {
            it.waitForExists(waitingTimeShort)
            it.clickAndWaitForNewWindow()
        }

    fun dismissAddonDownloadCompletedPrompt(addonName: String) {
        mDevice.waitForWindowUpdate(packageName, waitingTime)
        val title = appContext.getString(addonsR.string.mozac_feature_addons_installed_dialog_title_2, addonName)
        mDevice.findObject(UiSelector().text(title)).waitForExists(waitingTime)
        mDevice.waitAndInteract(
            Until.findObject(
                By.text(getStringResource(addonsR.string.mozac_feature_addons_installed_dialog_okay_button_2))
            )
        ) {}
        mDevice
            .findObject(
                UiSelector()
                    .textContains(getStringResource(addonsR.string.mozac_feature_addons_installed_dialog_okay_button_2))
            )
            .click()
    }

    fun clickEnableSwitch() {
        onView(withId(R.id.enable_switch)).perform(click())
        mDevice.waitForIdle()
    }

    fun goBack() {
        mDevice.pressBack()
        mDevice.waitForIdle()
    }

    /** The add-on is listed under the section with the title [sectionTitle] (Enabled, Disabled, Recommended). */
    fun verifyAddonInSection(addonName: String, sectionTitle: Int) {
        assertTrue(mDevice.findObject(UiSelector().text(getStringResource(sectionTitle))).waitForExists(waitingTime))
        // The section title is the first list item above the add-on's row.
        val section = mDevice.findObject(UiSelector().text(getStringResource(sectionTitle)))
        val row = itemWithResIdContainingText("$packageName:id/add_on_name", addonName)
        assertTrue(row.waitForExists(waitingTime))
        assertTrue("$addonName is not below that section", section.bounds.top < row.bounds.top)
    }

    fun clickFindMoreExtensions() {
        val label = getStringResource(addonsR.string.mozac_feature_addons_find_more_extensions_button_text)
        scrollToElementByText(label)
        mDevice.findObject(UiSelector().text(label)).click()
    }

    /** Taps Remove and confirms it in the dialog that follows. */
    fun clickRemoveAddonButton() {
        removeAddonButton().click()
        val confirm =
            mDevice.findObject(
                UiSelector()
                    .text(getStringResource(addonsR.string.mozac_feature_addons_remove))
                    .className("android.widget.Button")
            )
        assertTrue(confirm.waitForExists(waitingTime))
        confirm.click()
        mDevice.waitForIdle()
    }

    class Transition {
        fun addonsManagerRobot(interact: AddonsManagerRobot.() -> Unit): AddonsManagerRobot.Transition {
            AddonsManagerRobot().interact()
            return AddonsManagerRobot.Transition()
        }
    }

    private fun installAddonButton(addonName: String) =
        onView(
            allOf(
                withContentDescription("Install $addonName"),
                isDescendantOfA(withId(R.id.add_on_item)),
                hasSibling(hasDescendant(withText(addonName))),
            )
        )

    private fun removeAddonButton() = onView(withId(R.id.remove_add_on))

    private fun selectInstallAddonButton(addonName: String) {
        mDevice.waitForIdle()
        mDevice.findObject(UiSelector().textContains(addonName)).waitForExists(waitingTime)

        installAddonButton(addonName).check(matches(isCompletelyDisplayed())).perform(click())
    }

    private fun assertAddonPrompt(addonName: String) {
        mDevice.waitForIdle()
        mDevice.findObject(UiSelector().resourceId("$packageName:id/title")).waitForExists(waitingTime)

        assertTrue(mDevice.findObject(UiSelector().textContains("Add $addonName")).waitForExists(waitingTime))

        onView(
                allOf(
                    withId(addonsR.id.optional_or_required_text),
                    withText(containsString("Required permissions:")),
                )
            )
            .check(matches(isCompletelyDisplayed()))

        onView(
                allOf(
                    withId(addonsR.id.allow_button),
                    withText(addonsR.string.mozac_feature_addons_permissions_dialog_add),
                )
            )
            .check(matches(isCompletelyDisplayed()))

        onView(
                allOf(
                    withId(addonsR.id.deny_button),
                    withText(addonsR.string.mozac_feature_addons_permissions_dialog_cancel),
                )
            )
            .check(matches(isCompletelyDisplayed()))
    }

    private fun cancelInstallButton() {
        onView(
                allOf(
                    withId(addonsR.id.deny_button),
                    withText(addonsR.string.mozac_feature_addons_permissions_dialog_cancel),
                )
            )
            .check(matches(isCompletelyDisplayed()))
            .perform(click())
    }

    private fun allowInstallAddonButton() {
        // The permissions dialog disables the "Add" button for ~1s as
        // clickjacking protection. Wait until it becomes enabled.
        val allowButton =
            mDevice.wait(
                Until.findObject(By.res("$packageName:id/allow_button").enabled(true)),
                waitingTime,
            )
        assertTrue("Allow button did not become enabled", allowButton != null)
        allowButton.click()
    }

    private fun assertAddonDownloadCompletedPrompt(addonName: String) {
        mDevice.waitForIdle()
        assertTrue(mDevice.findObject(UiSelector().textContains("$addonName was added")).waitForExists(waitingTime))
    }

    private fun waitForDownloadProgressUntilGone() {
        mDevice.waitForIdle()
        mDevice.findObject(UiSelector().resourceId("$packageName:id/addonProgressOverlay")).waitUntilGone(waitingTime)
    }

    private fun assertAddonElementsView(addonName: String) {
        mDevice.waitForIdle()
        mDevice.findObject(UiSelector().textContains(addonName)).waitForExists(waitingTime)

        onView(
                allOf(
                    withId(R.id.remove_add_on),
                    hasSibling(withId(R.id.enable_switch)),
                    hasSibling(withId(R.id.settings)),
                    hasSibling(withId(R.id.details)),
                    hasSibling(withId(R.id.permissions)),
                    hasSibling(withId(R.id.allow_in_private_browsing_switch)),
                    hasSibling(withId(R.id.check_for_updates)),
                )
            )
            .check(matches(withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))
    }
}
