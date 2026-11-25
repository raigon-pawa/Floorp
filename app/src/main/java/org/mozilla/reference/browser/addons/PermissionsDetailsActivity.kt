/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.addons

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import mozilla.components.feature.addons.Addon
import mozilla.components.feature.addons.AddonManagerException
import mozilla.components.feature.addons.R as addonsR
import mozilla.components.feature.addons.ui.translateName
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.utils.ext.getParcelableExtraCompat
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.ext.enableEdgeToEdgeFromAndroid13

private const val LEARN_MORE_URL = "https://support.mozilla.org/kb/permission-request-messages-firefox-extensions"

/**
 * Shows the permissions of an add-on: the required ones and its data collection, then the optional permissions, site
 * access and data collection with a switch each, so a grant made after install can be seen and revoked.
 */
class PermissionsDetailsActivity : AppCompatActivity(), View.OnClickListener {
    private val logger = Logger("PermissionsDetailsActivity")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_on_permissions)
        enableEdgeToEdgeFromAndroid13()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val addon = requireNotNull(intent.getParcelableExtraCompat("add_on", Addon::class.java))

        title = addon.translateName(this)

        bindPermissions(addon)
        // The add-on handed over may predate grants made since (in its own settings, or here before a rotation).
        lifecycleScope.launch {
            try {
                components.core.addonManager.getAddonByID(addon.id)?.let { bindPermissions(it) }
            } catch (e: AddonManagerException) {
                logger.error("Failed to reload add-on ${addon.id}", e)
            }
        }

        findViewById<View>(R.id.learn_more_label).setOnClickListener(this)
    }

    private fun bindPermissions(addon: Addon) {
        val list = findViewById<ViewGroup>(R.id.add_ons_permissions)
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)

        val required = addon.translatePermissions(this).sorted()
        if (required.isNotEmpty()) {
            addHeader(
                inflater,
                list,
                addonsR.string.mozac_feature_addons_permissions_dialog_heading_required_permissions,
            )
            required.forEach { addRow(inflater, list, it) }
        }
        val collected = addon.translateRequiredDataCollectionPermissions(this)
        if (collected.isNotEmpty()) {
            addHeader(
                inflater,
                list,
                addonsR.string.mozac_feature_addons_permissions_dialog_heading_required_data_collection,
            )
            val description = addonsR.string.mozac_feature_addons_permissions_required_data_collection_description_2
            addRow(inflater, list, getString(description, Addon.formatLocalizedDataCollectionPermissions(collected)))
        }

        val origins =
            addon.optionalOrigins.map {
                Addon.LocalizedPermission(Addon.localizePermissions(listOf(it.name), this).firstOrNull() ?: it.name, it)
            }
        val optional =
            addon.translateOptionalPermissions(this).map { it to OptionalKind.PERMISSION } +
                origins.map { it to OptionalKind.ORIGIN } +
                addon.translateOptionalDataCollectionPermissions(this).map { it to OptionalKind.DATA_COLLECTION }
        if (optional.isNotEmpty()) {
            addHeader(inflater, list, addonsR.string.mozac_feature_addons_permissions_dialog_heading_optional_settings)
            optional.forEach { (permission, kind) -> addSwitch(inflater, list, addon, permission, kind) }
        }
    }

    private fun addHeader(inflater: LayoutInflater, list: ViewGroup, @StringRes text: Int) {
        val header = inflater.inflate(R.layout.add_ons_permission_header, list, false) as TextView
        header.setText(text)
        list.addView(header)
    }

    private fun addRow(inflater: LayoutInflater, list: ViewGroup, text: String) {
        val row = inflater.inflate(R.layout.add_ons_permission_item, list, false)
        row.findViewById<TextView>(R.id.permission).text = text
        list.addView(row)
    }

    private fun addSwitch(
        inflater: LayoutInflater,
        list: ViewGroup,
        addon: Addon,
        permission: Addon.LocalizedPermission,
        kind: OptionalKind,
    ) {
        val switch = inflater.inflate(R.layout.add_ons_permission_switch_item, list, false) as SwitchCompat
        switch.text = permission.localizedName
        switch.isChecked = permission.permission.granted
        switch.setOnCheckedChangeListener { view, granted ->
            view.isEnabled = false
            setOptionalPermission(addon, permission.permission.name, kind, granted)
        }
        list.addView(switch)
    }

    private fun setOptionalPermission(addon: Addon, name: String, kind: OptionalKind, granted: Boolean) {
        val names = listOf(name)
        val permissions = if (kind == OptionalKind.PERMISSION) names else emptyList()
        val origins = if (kind == OptionalKind.ORIGIN) names else emptyList()
        val dataCollection = if (kind == OptionalKind.DATA_COLLECTION) names else emptyList()
        val onSuccess: (Addon) -> Unit = { bindPermissions(it) }
        val onError: (Throwable) -> Unit = { e ->
            logger.error("Failed to change optional permission $name", e)
            bindPermissions(addon)
        }
        val manager = components.core.addonManager
        if (granted) {
            manager.addOptionalPermission(addon, permissions, origins, dataCollection, onSuccess, onError)
        } else {
            manager.removeOptionalPermission(addon, permissions, origins, dataCollection, onSuccess, onError)
        }
    }

    private enum class OptionalKind {
        PERMISSION,
        ORIGIN,
        DATA_COLLECTION,
    }

    override fun onClick(v: View?) = openInBrowserTab(LEARN_MORE_URL)

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
