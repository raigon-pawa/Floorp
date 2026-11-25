/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import android.os.Bundle
import androidx.annotation.StringRes
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
                            isIconSpaceReserved = false
                            isSingleLineTitle = false
                            setOnPreferenceClickListener {
                                onClick()
                                true
                            }
                        }
                    )

                LICENSES.forEach { (title, asset, components) ->
                    row(title, components.joinToString(", ")) { showPage(TextPageFragment.fromAsset(title, asset)) }
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
                row(R.string.archwave_licenses_play, PLAY_SERVICES) { openInNewTab(PLAY_SERVICES_TERMS) }
            }
    }

    override fun onResume() {
        super.onResume()
        (activity as SettingsFragment.ActionBarUpdater).updateTitle(R.string.archwave_about_licenses)
    }

    private data class License(
        @StringRes val title: Int,
        val asset: String,
        val components: List<String>,
    )

    private companion object {
        val LICENSES =
            listOf(
                License(
                    R.string.archwave_licenses_mpl,
                    "licenses/MPL-2.0.txt",
                    listOf(
                        "Archwave",
                        "Mozilla Android Components",
                        "GeckoView",
                        "Mozilla Application Services",
                        "Glean",
                    ),
                ),
                License(
                    R.string.archwave_licenses_apache,
                    "licenses/Apache-2.0.txt",
                    listOf(
                        "AndroidX (Android Jetpack)",
                        "Kotlin standard library",
                        "kotlinx.coroutines",
                        "kotlinx.serialization",
                        "Material Components for Android",
                        "Guava",
                        "ZXing",
                        "AndroidSVG",
                        "DiskLruCache",
                        "SnakeYAML",
                        "JNA (used under the Apache License)",
                        "JetBrains Java Annotations",
                        "JSpecify",
                        "Error Prone annotations",
                    ),
                ),
                License(R.string.archwave_licenses_ofl, "licenses/JetBrainsMono-OFL.txt", listOf("JetBrains Mono")),
            )
        // The notices both projects publish for their binaries, at the versions the app ships (megazords/full/
        // DEPENDENCIES.md of application-services v160.0, DEPENDENCIES.md of glean v70.3.0); replace them on an update.
        val NATIVE_LIBRARIES =
            listOf(
                R.string.archwave_licenses_native_app_services to "licenses/application-services-dependencies.md",
                R.string.archwave_licenses_native_glean to "licenses/glean-dependencies.md",
            )
        const val GECKO_LICENSES = "about:license"
        const val PLAY_SERVICES =
            "Google Play services (base, basement, FIDO, tasks), used by GeckoView for security keys"
        const val PLAY_SERVICES_TERMS = "https://developer.android.com/studio/terms"
    }
}
