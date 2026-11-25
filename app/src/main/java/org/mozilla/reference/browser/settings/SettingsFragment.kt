/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.Preference.OnPreferenceChangeListener
import androidx.preference.Preference.OnPreferenceClickListener
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.SearchState
import mozilla.components.browser.state.state.searchEngines
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.ktx.android.view.showKeyboard
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.R.string.pref_key_about_page
import org.mozilla.reference.browser.R.string.pref_key_make_default_browser
import org.mozilla.reference.browser.R.string.pref_key_override_amo_collection
import org.mozilla.reference.browser.R.string.pref_key_privacy
import org.mozilla.reference.browser.R.string.pref_key_remote_debugging
import org.mozilla.reference.browser.ext.getPreferenceKey
import org.mozilla.reference.browser.ext.requireComponents

private typealias RBSettings = org.mozilla.reference.browser.settings.Settings

/** Top-level settings page. */
class SettingsFragment : PreferenceFragmentCompat() {
    /** Implemented by the hosting activity, which owns the action bar. */
    interface ActionBarUpdater {
        /** Called on navigation because the fragments are swapped in one container and the title does not follow. */
        fun updateTitle(titleResId: Int)
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<Preference>(requireContext().getPreferenceKey(R.string.pref_key_clear_browsing_data))
            ?.onPreferenceClickListener =
            OnPreferenceClickListener {
                ClearDataDialogFragment.show(childFragmentManager)
                true
            }

        searchEnginePreference()?.onPreferenceChangeListener = OnPreferenceChangeListener { _, id ->
            val engine = requireComponents.core.store.state.search.searchEngines.find { it.id == id }
            engine?.let { requireComponents.useCases.searchUseCases.selectSearchEngine(it) }
            engine != null
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        // Engines load asynchronously at start-up, and the choice can change elsewhere; follow the store.
        viewLifecycleOwner.lifecycleScope.launch {
            requireComponents.core.store
                .flow(viewLifecycleOwner)
                .map { it.search }
                .distinctUntilChanged()
                .collect(::showSearchEngines)
        }
    }

    private fun searchEnginePreference() =
        findPreference<ListPreference>(requireContext().getPreferenceKey(R.string.pref_key_search_engine))

    private fun showSearchEngines(search: SearchState) {
        val preference = searchEnginePreference() ?: return
        val engines = search.searchEngines
        val current = search.selectedOrDefaultSearchEngine
        preference.isEnabled = search.complete && current != null
        if (!search.complete || current == null) {
            preference.setSummary(R.string.archwave_settings_search_engine_loading)
            return
        }
        preference.entries = engines.map { it.name }.toTypedArray()
        preference.entryValues = engines.map { it.id }.toTypedArray()
        preference.value = current.id
        preference.summary = current.name
    }

    override fun onResume() {
        super.onResume()

        setupPreferences()
        getActionBarUpdater().apply {
            updateTitle(R.string.settings)
        }
        ClearDataDialogFragment.showIfInterrupted(requireContext(), childFragmentManager)
    }

    private fun setupPreferences() {
        val makeDefaultBrowserKey = requireContext().getPreferenceKey(pref_key_make_default_browser)
        val remoteDebuggingKey = requireContext().getPreferenceKey(pref_key_remote_debugging)
        val aboutPageKey = requireContext().getPreferenceKey(pref_key_about_page)
        val privacyKey = requireContext().getPreferenceKey(pref_key_privacy)
        val customAddonsKey = requireContext().getPreferenceKey(pref_key_override_amo_collection)

        val preferenceMakeDefaultBrowser = findPreference<Preference>(makeDefaultBrowserKey)
        val preferenceRemoteDebugging = findPreference<SwitchPreferenceCompat>(remoteDebuggingKey)
        val preferenceAboutPage = findPreference<Preference>(aboutPageKey)
        val preferencePrivacy = findPreference<Preference>(privacyKey)
        val preferenceCustomAddons = findPreference<Preference>(customAddonsKey)

        preferenceMakeDefaultBrowser?.onPreferenceClickListener = getClickListenerForMakeDefaultBrowser()
        preferenceRemoteDebugging?.onPreferenceChangeListener = getChangeListenerForRemoteDebugging()
        preferenceAboutPage?.onPreferenceClickListener = getAboutPageListener()
        preferencePrivacy?.onPreferenceClickListener = getClickListenerForPrivacy()
        preferenceCustomAddons?.onPreferenceClickListener = getClickListenerForCustomAddons()
    }

    private fun getClickListenerForMakeDefaultBrowser(): OnPreferenceClickListener = OnPreferenceClickListener {
        val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        startActivity(intent)
        true
    }

    private fun getClickListenerForPrivacy(): OnPreferenceClickListener = OnPreferenceClickListener {
        parentFragmentManager
            .beginTransaction()
            .replace(R.id.container, PrivacySettingsFragment())
            .addToBackStack(null)
            .commit()
        true
    }

    private fun getChangeListenerForRemoteDebugging(): OnPreferenceChangeListener =
        OnPreferenceChangeListener { _, newValue ->
            requireComponents.core.engine.settings.remoteDebuggingEnabled = newValue as Boolean
            true
        }

    private fun getAboutPageListener(): OnPreferenceClickListener = OnPreferenceClickListener {
        parentFragmentManager.beginTransaction().replace(R.id.container, AboutFragment()).addToBackStack(null).commit()
        true
    }

    private fun getActionBarUpdater() = activity as ActionBarUpdater

    private fun getClickListenerForCustomAddons(): OnPreferenceClickListener = OnPreferenceClickListener {
        val context = requireContext()
        val dialogView = View.inflate(context, R.layout.amo_collection_override_dialog, null)
        val userView = dialogView.findViewById<EditText>(R.id.custom_amo_user)
        val collectionView = dialogView.findViewById<EditText>(R.id.custom_amo_collection)

        AlertDialog.Builder(context)
            .apply {
                setTitle(context.getString(R.string.preferences_customize_amo_collection))
                setMessage(R.string.archwave_addons_custom_collection_message)
                setView(dialogView)
                setNegativeButton(R.string.customize_addon_collection_cancel) { dialog: DialogInterface, _ ->
                    dialog.cancel()
                }

                setPositiveButton(R.string.customize_addon_collection_ok) { _, _ ->
                    // Only both fields together select a collection; restart only if the one in use changes.
                    fun collectionInUse() =
                        (RBSettings.getOverrideAmoUser(context) to RBSettings.getOverrideAmoCollection(context))
                            .takeIf { RBSettings.isAmoCollectionOverrideConfigured(context) }
                    val before = collectionInUse()
                    RBSettings.setOverrideAmoUser(context, userView.text.toString())
                    RBSettings.setOverrideAmoCollection(context, collectionView.text.toString())
                    if (collectionInUse() == before) return@setPositiveButton

                    Toast.makeText(
                            context,
                            getString(R.string.toast_customize_addon_collection_done),
                            Toast.LENGTH_LONG,
                        )
                        .show()

                    // The add-on provider reads the collection once per process, so start the app again in a new one.
                    Handler(Looper.getMainLooper())
                        .postDelayed(
                            {
                                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                launch?.component?.let { context.startActivity(Intent.makeRestartActivityTask(it)) }
                                exitProcess(0)
                            },
                            AMO_COLLECTION_OVERRIDE_EXIT_DELAY,
                        )
                }

                collectionView.setText(RBSettings.getOverrideAmoCollection(context))
                userView.setText(RBSettings.getOverrideAmoUser(context))
                userView.requestFocus()
                userView.showKeyboard()
                create()
            }
            .show()
        true
    }

    companion object {
        private const val AMO_COLLECTION_OVERRIDE_EXIT_DELAY = 3000L
    }
}
