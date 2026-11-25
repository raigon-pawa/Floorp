/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.tabs.synced

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.ext.enableEdgeToEdgeFromAndroid13

/** Activity whose only purpose is to host the [SyncedTabsFragment], opened from the browser menu. */
class SyncedTabsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdgeFromAndroid13(true)

        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().apply {
                replace(
                    R.id.container,
                    SyncedTabsFragment(),
                )
                commit()
            }
        }
    }
}
