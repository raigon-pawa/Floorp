/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.ext

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import mozilla.components.support.ktx.android.view.setupPersistentInsets

/**
 * Draws this activity edge to edge behind transparent system bars and pads its content for them, from Android 13 on.
 *
 * Below 13 setupPersistentInsets does nothing, so edge to edge would put the content under the status bar, action bar
 * and navigation bar. Those versions keep the classic window, which the system lays out between the bars itself.
 *
 * @param consumeInsets passed to setupPersistentInsets: true stops views below the root from applying the insets again.
 */
fun ComponentActivity.enableEdgeToEdgeFromAndroid13(consumeInsets: Boolean = false) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
    window.setupPersistentInsets(consumeInsets)
}
