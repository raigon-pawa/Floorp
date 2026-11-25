/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.customtabs

import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsSessionToken
import mozilla.components.concept.engine.Engine
import mozilla.components.feature.customtabs.AbstractCustomTabsService
import mozilla.components.feature.customtabs.store.CustomTabsServiceStore
import org.mozilla.reference.browser.ext.components

/**
 * Service that apps using the Custom Tabs protocol bind to, so they can warm up the engine and hint likely URLs before
 * opening a custom tab here.
 */
class CustomTabsService : AbstractCustomTabsService() {
    override val customTabsServiceStore: CustomTabsServiceStore by lazy { components.core.customTabsStore }
    override val engine: Engine by lazy { components.core.engine }

    /**
     * Accepts the hint and does nothing with it: any installed app may call this, and the base class would open
     * connections to up to 51 hosts of its choice before the user has seen anything.
     */
    override fun mayLaunchUrl(
        sessionToken: CustomTabsSessionToken,
        url: Uri?,
        extras: Bundle?,
        otherLikelyBundles: List<Bundle>?,
    ) = true
}
