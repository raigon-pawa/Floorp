/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.search

import mozilla.components.concept.storage.HistoryStorage
import mozilla.components.concept.storage.SearchResult

/**
 * History for address bar suggestions without the plain-HTTP address of a page that was upgraded to HTTPS (HTTPS-only
 * mode, or a site's own redirect): history records both, the first without a title, so one visit gave two rows.
 */
fun HistoryStorage.withoutHttpsUpgradeHops(): HistoryStorage {
    val storage = this
    return object : HistoryStorage by storage {
        override fun getSuggestions(query: String, limit: Int) =
            withoutHttpsUpgradeHops(storage.getSuggestions(query, limit))
    }
}

/** Drops each http:// result whose https:// address is among the [results]. */
fun withoutHttpsUpgradeHops(results: List<SearchResult>): List<SearchResult> {
    val secure = results.mapNotNull { it.url.removePrefixOrNull("https://") }.toSet()
    return results.filterNot { it.url.removePrefixOrNull("http://") in secure }
}

private fun String.removePrefixOrNull(prefix: String) = if (startsWith(prefix)) removePrefix(prefix) else null
