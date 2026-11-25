/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import android.os.Bundle
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.mozilla.reference.browser.R

/**
 * The bundled components and their licences. The list follows the release build's runtime dependencies (`./gradlew
 * :app:dependencies --configuration releaseRuntimeClasspath`) and the licences their Maven POM files declare; update it
 * when a dependency is added or removed.
 */
class LicensesFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val context = requireContext()
        preferenceScreen =
            preferenceManager.createPreferenceScreen(context).apply {
                fun row(
                    @StringRes title: Int,
                    summary: String,
                    onClick: () -> Unit,
                ) =
                    addPreference(
                        Preference(context).apply {
                            setTitle(title)
                            this.summary = summary
                            isSingleLineTitle = false
                            setOnPreferenceClickListener {
                                onClick()
                                true
                            }
                        }
                    )

                LICENSES.forEach { (title, asset, components) ->
                    row(title, resources.getStringArray(components).joinToString(", ")) {
                        showPage(TextPageFragment.fromAsset(title, asset))
                    }
                }
                NATIVE_LIBRARIES.forEach { (title, asset) ->
                    row(title, getString(R.string.archwave_licenses_native_summary)) {
                        showPage(TextPageFragment.fromAsset(title, asset))
                    }
                }
                row(R.string.archwave_licenses_gecko, getString(R.string.archwave_licenses_gecko_summary)) {
                    openInNewTab(GECKO_LICENSES)
                }
                // Not open source; the terms are only linked.
                row(R.string.archwave_licenses_play, getString(R.string.archwave_licenses_play_summary)) {
                    openInNewTab(PLAY_SERVICES_TERMS)
                }
            }
    }

    override fun onResume() {
        super.onResume()
        (activity as SettingsFragment.ActionBarUpdater).updateTitle(R.string.archwave_about_licenses)
    }

    private data class License(
        @StringRes val title: Int,
        val asset: String,
        @ArrayRes val components: Int,
    )

    internal companion object {
        private val LICENSES =
            listOf(
                License(
                    R.string.archwave_licenses_mpl,
                    "licenses/MPL-2.0.txt",
                    R.array.archwave_licenses_mpl_components,
                ),
                License(
                    R.string.archwave_licenses_apache,
                    "licenses/Apache-2.0.txt",
                    R.array.archwave_licenses_apache_components,
                ),
                License(
                    R.string.archwave_licenses_ofl,
                    "licenses/JetBrainsMono-OFL.txt",
                    R.array.archwave_licenses_ofl_components,
                ),
            )
        // The notices both projects publish for their binaries, at the versions the app ships (megazords/full/
        // DEPENDENCIES.md of application-services v160.0, DEPENDENCIES.md of glean v70.3.0); replace them on an update.
        private val NATIVE_LIBRARIES =
            listOf(
                R.string.archwave_licenses_native_app_services to "licenses/application-services-dependencies.md",
                R.string.archwave_licenses_native_glean to "licenses/glean-dependencies.md",
            )
        private const val GECKO_LICENSES = "about:license"
        private const val PLAY_SERVICES_TERMS = "https://developer.android.com/studio/terms"

        /** Every licence text the list opens, read from the assets by name (a missing one crashes on tap). */
        @VisibleForTesting
        internal val assetPaths: List<String>
            get() = LICENSES.map { it.asset } + NATIVE_LIBRARIES.map { it.second }
    }
}
