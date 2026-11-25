/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import android.content.Context
import androidx.annotation.BoolRes
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import mozilla.components.concept.engine.Engine
import org.mozilla.reference.browser.R

/** Typed access to preferences read from code. */
object Settings {
    /** Empty when unset, so callers can test for an override with [isAmoCollectionOverrideConfigured]. */
    fun getOverrideAmoUser(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(
                context.getString(R.string.pref_key_override_amo_user),
                "",
            ) ?: ""

    /** Empty when unset, so callers can test for an override with [isAmoCollectionOverrideConfigured]. */
    fun getOverrideAmoCollection(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(
                context.getString(R.string.pref_key_override_amo_collection),
                "",
            ) ?: ""

    /**
     * Takes effect only after a restart, because the add-on provider reads the override once per process. The settings
     * dialog that calls this exits the process for that reason.
     */
    fun setOverrideAmoUser(
        context: Context,
        value: String,
    ) {
        val key = context.getString(R.string.pref_key_override_amo_user)
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(key, value)
        }
    }

    /** Like [setOverrideAmoUser], takes effect only after a restart. */
    fun setOverrideAmoCollection(
        context: Context,
        value: String,
    ) {
        val key = context.getString(R.string.pref_key_override_amo_collection)
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(key, value)
        }
    }

    /** Both values are required because an AMO collection is addressed by its owner and its name together. */
    fun isAmoCollectionOverrideConfigured(context: Context): Boolean =
        getOverrideAmoUser(context).isNotEmpty() && getOverrideAmoCollection(context).isNotEmpty()

    /** Tracking protection in normal tabs. */
    fun isTrackingProtectionNormal(context: Context): Boolean =
        bool(context, R.string.pref_key_tracking_protection_normal, R.bool.pref_default_tracking_protection_normal)

    /** Tracking protection in private tabs. */
    fun isTrackingProtectionPrivate(context: Context): Boolean =
        bool(context, R.string.pref_key_tracking_protection_private, R.bool.pref_default_tracking_protection_private)

    /** HTTPS-only mode for all tabs. */
    fun isHttpsOnly(context: Context): Boolean =
        bool(context, R.string.pref_key_https_only, R.bool.pref_default_https_only)

    /** Global Privacy Control signal. */
    fun isGlobalPrivacyControl(context: Context): Boolean =
        bool(context, R.string.pref_key_global_privacy_control, R.bool.pref_default_global_privacy_control)

    /** Network search suggestions in the address bar (normal tabs only). */
    fun isSearchSuggestions(context: Context): Boolean =
        bool(context, R.string.pref_key_search_suggestions, R.bool.pref_default_search_suggestions)

    /** Links that other apps send open in a private tab. Read for each incoming link, so a change applies at once. */
    fun isOpenLinksInPrivate(context: Context): Boolean =
        bool(context, R.string.pref_key_open_links_in_private, R.bool.pref_default_open_links_in_private)

    /** The DNS-over-HTTPS resolver URL; empty means the system resolver. */
    fun getDohProviderUrl(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(
                context.getString(R.string.pref_key_doh_provider),
                context.getString(R.string.pref_default_doh_provider),
            ) ?: ""

    /**
     * Engine mode for a stored resolver URL. [Engine.DohSettingsMode.INCREASED] is "DoH first, fall back to the system
     * resolver"; [Engine.DohSettingsMode.OFF] turns DoH off by choice (DEFAULT would mean "off until Gecko decides").
     */
    fun dohModeFor(providerUrl: String): Engine.DohSettingsMode =
        if (providerUrl.isEmpty()) Engine.DohSettingsMode.OFF else Engine.DohSettingsMode.INCREASED

    /** Turns the HTTPS-only switch into the mode the engine expects (on means normal and private tabs). */
    fun httpsOnlyModeFor(enabled: Boolean): Engine.HttpsOnlyMode =
        if (enabled) Engine.HttpsOnlyMode.ENABLED else Engine.HttpsOnlyMode.DISABLED

    private fun bool(
        context: Context,
        @StringRes key: Int,
        @BoolRes default: Int,
    ): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(context.getString(key), context.resources.getBoolean(default))
}
