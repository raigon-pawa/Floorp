/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.addons

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import mozilla.components.feature.addons.Addon
import mozilla.components.feature.addons.AddonManagerException
import mozilla.components.feature.addons.R as addonsR
import mozilla.components.feature.addons.ui.toLocalizedString
import mozilla.components.feature.addons.ui.translateName
import mozilla.components.feature.addons.update.AddonUpdater
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.android.view.setupPersistentInsets
import mozilla.components.support.utils.ext.getParcelableExtraCompat
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.ext.components

/** An activity to show the details of a installed add-on. */
class InstalledAddonDetailsActivity : AppCompatActivity() {
    private val logger = Logger("InstalledAddonDetailsActivity")

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_installed_add_on_details)
        window.setupPersistentInsets(true)

        val addon =
            requireNotNull(intent.getParcelableExtraCompat("add_on", Addon::class.java)).also {
                bindUI(it)
            }

        bindAddon(addon)
    }

    private fun bindAddon(addon: Addon) {
        lifecycleScope.launch {
            try {
                // Reads the engine's state only; unlike getAddons() this never waits for the catalogue.
                val installed =
                    components.core.addonManager.getAddonByID(addon.id)
                        ?: throw AddonManagerException(Exception("Addon ${addon.id} not found"))

                bindUI(installed)
            } catch (e: AddonManagerException) {
                logger.error("Failed to load add-on ${addon.id}", e)
                Toast.makeText(
                        baseContext,
                        addonsR.string.mozac_feature_addons_failed_to_load_extensions,
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }
        }
    }

    private fun bindUI(addon: Addon) {
        title = addon.translateName(this)

        bindEnableSwitch(addon)

        bindSettings(addon)

        bindDetails(addon)

        bindPermissions(addon)

        bindAllowInPrivateBrowsingSwitch(addon)

        bindCheckForUpdates(addon)

        bindRemoveButton(addon)
    }

    private fun bindEnableSwitch(addon: Addon) {
        val switch = findViewById<SwitchCompat>(R.id.enable_switch)
        // bindUI runs again once the add-on is loaded: the previous listener must not see that update as a tap.
        switch.setOnCheckedChangeListener(null)
        switch.setState(addon.isEnabled())
        switch.setOnCheckedChangeListener { _, isChecked ->
            val onError: (Throwable) -> Unit = {
                bindAddon(addon)
                val message =
                    if (isChecked) {
                        addonsR.string.mozac_feature_addons_failed_to_enable
                    } else {
                        addonsR.string.mozac_feature_addons_failed_to_disable
                    }
                Toast.makeText(this, getString(message, addon.translateName(this)), Toast.LENGTH_SHORT).show()
            }
            if (isChecked) {
                this.components.core.addonManager.enableAddon(
                    addon,
                    onSuccess = {
                        switch.setState(true)
                    },
                    onError = onError,
                )
            } else {
                this.components.core.addonManager.disableAddon(
                    addon,
                    onSuccess = {
                        switch.setState(false)
                    },
                    onError = onError,
                )
            }
        }
    }

    private fun bindSettings(addon: Addon) {
        val view = findViewById<View>(R.id.settings)
        view.isVisible = shouldSettingsBeVisible(addon)
        view.isEnabled = shouldSettingsBeVisible(addon)
        view.setOnClickListener {
            // A tab has what a bare engine view lacks: select boxes, dialogs, file pickers, downloads, links.
            addon.installedState?.optionsPageUrl?.let { openInBrowserTab(it) }
        }
    }

    private fun bindDetails(addon: Addon) {
        findViewById<View>(R.id.details).setOnClickListener {
            val intent = Intent(this, AddonDetailsActivity::class.java)
            intent.putExtra("add_on", addon)
            this.startActivity(intent)
        }
    }

    private fun bindPermissions(addon: Addon) {
        findViewById<View>(R.id.permissions).setOnClickListener {
            val intent = Intent(this, PermissionsDetailsActivity::class.java)
            intent.putExtra("add_on", addon)
            this.startActivity(intent)
        }
    }

    private fun bindAllowInPrivateBrowsingSwitch(addon: Addon) {
        val switch = findViewById<SwitchCompat>(R.id.allow_in_private_browsing_switch)
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = addon.isAllowedInPrivateBrowsing()
        switch.setOnCheckedChangeListener { _, isChecked ->
            this.components.core.addonManager.setAddonAllowedInPrivateBrowsing(
                addon,
                isChecked,
                onSuccess = {
                    switch.isChecked = isChecked
                },
            )
        }
    }

    private fun bindCheckForUpdates(addon: Addon) {
        val view = findViewById<TextView>(R.id.check_for_updates)
        view.setOnClickListener {
            view.isEnabled = false
            view.setText(R.string.archwave_addons_checking_for_updates)
            components.core.addonManager.updateAddon(addon.id) { status ->
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    view.isEnabled = true
                    view.setText(R.string.archwave_addons_check_for_updates)
                    val message =
                        when (status) {
                            is AddonUpdater.Status.Error -> getString(R.string.archwave_addons_update_check_failed)
                            else -> status.toLocalizedString(this)
                        }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    if (status == AddonUpdater.Status.SuccessfullyUpdated) bindAddon(addon)
                }
            }
        }
    }

    private fun bindRemoveButton(addon: Addon) {
        findViewById<View>(R.id.remove_add_on).setOnClickListener {
            this.components.core.addonManager.uninstallAddon(
                addon,
                onSuccess = {
                    finish()
                },
                onError = { _, _ ->
                    val message =
                        getString(addonsR.string.mozac_feature_addons_failed_to_uninstall, addon.translateName(this))
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

    private fun SwitchCompat.setState(checked: Boolean) {
        isChecked = checked
    }

    private fun shouldSettingsBeVisible(addon: Addon) = !addon.installedState?.optionsPageUrl.isNullOrEmpty()
}
