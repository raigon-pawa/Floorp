/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The theme recolours Android Components by declaring resources under the library's own names (archwave_theme.xml,
 * colors.xml, style.xml). When the library renames or drops one, the app's copy silently becomes an unused resource and
 * the library's colour is back. Lint cannot tell, so this test does: keep the lists in step with those files.
 */
class ThemeOverridesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // R class of the library that owns each resource -> the names the app overrides.
    private val colours =
        mapOf(
            "mozilla.components.browser.menu" to listOf("mozac_browser_menu_background"),
            "mozilla.components.browser.menu2" to listOf("mozac_browser_menu2_background"),
            "mozilla.components.feature.addons" to
                listOf(
                    "mozac_feature_addons_text_color_accent",
                    "mozac_feature_addons_messagebar_warning_background_color",
                    "mozac_feature_addons_messagebar_error_background_color",
                ),
            "mozilla.components.feature.contextmenu" to
                listOf("mozac_additional_note_background", "mozac_additional_note_text_color"),
            "mozilla.components.feature.downloads" to listOf("mozac_feature_downloads_notification"),
            "mozilla.components.feature.prompts" to listOf("mozac_feat_prompt_cancel_save_dialog_text_color"),
            "mozilla.components.feature.qr" to listOf("mozac_feature_qr_scan_success_color"),
            "mozilla.components.feature.readerview" to
                listOf(
                    "mozac_feature_readerview_text_color",
                    "mozac_feature_readerview_selected",
                    "mozac_feature_readerview_text_color_disabled",
                ),
            "mozilla.components.ui.tabcounter" to listOf("mozac_ui_tabcounter_default_tint"),
            "mozilla.components.ui.widgets" to listOf("mozac_destructive_button_text_color"),
        )
    private val styles =
        mapOf(
            "mozilla.components.feature.prompts" to "MozDialogStyle",
            "mozilla.components.lib.crash" to "Theme_Mozac_CrashReporter",
        )

    private fun libraryId(
        library: String,
        type: String,
        name: String,
    ): Int =
        try {
            Class.forName("$library.R\$$type").getField(name).getInt(null)
        } catch (e: ReflectiveOperationException) {
            fail("$library no longer has the $type $name: rename or remove the app's override ($e)")
            0
        }

    @Test
    fun overriddenLibraryStylesStillExist() {
        styles.forEach { (library, name) -> libraryId(library, "style", name) }
    }

    @Test
    fun overriddenLibraryColoursAreMonochrome() {
        val meaningful = setOf(context.getColor(R.color.aw_error), context.getColor(R.color.aw_warning))
        for ((library, names) in colours) {
            for (name in names) {
                val colour = context.getColor(libraryId(library, "color", name))
                val grey = Color.red(colour) == Color.green(colour) && Color.green(colour) == Color.blue(colour)
                assertTrue("$name is #%08x".format(colour), grey || colour in meaningful)
            }
        }
    }
}
