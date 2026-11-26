/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.widget.Toast
import mozilla.components.browser.errorpages.ErrorPages
import mozilla.components.browser.state.selector.findTabOrCustomTab
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.request.ErrorType
import mozilla.components.concept.engine.request.RequestInterceptor
import mozilla.components.support.webextensions.WebExtensionSupport
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.tabs.PrivatePage

/** Serves the app's own about: pages and hands other loads to the app links interceptor. */
class AppRequestInterceptor(private val context: Context) : RequestInterceptor {
    override fun onLoadRequest(
        engineSession: EngineSession,
        uri: String,
        lastUri: String?,
        hasUserGesture: Boolean,
        isSameDomain: Boolean,
        isRedirect: Boolean,
        isDirectNavigation: Boolean,
        isSubframeRequest: Boolean,
    ): RequestInterceptor.InterceptionResponse? {
        if (blockedInPrivateTab(engineSession, uri)) return RequestInterceptor.InterceptionResponse.Deny
        return when (uri) {
            "about:privatebrowsing" -> {
                val page = PrivatePage.createPrivateBrowsingPage(context)
                RequestInterceptor.InterceptionResponse.Content(page, encoding = "base64")
            }

            "about:crashes" -> {
                val intent = Intent(context, CrashListActivity::class.java)
                intent.addFlags(FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)

                RequestInterceptor.InterceptionResponse.Url("about:blank")
            }

            else -> {
                // Firefox Accounts is dormant: asking services.accountsAuthFeature here would start the account
                // manager on the first page load.
                context.components.services.appLinksInterceptor.onLoadRequest(
                    engineSession,
                    uri,
                    lastUri,
                    hasUserGesture,
                    isSameDomain,
                    isRedirect,
                    isDirectNavigation,
                    isSubframeRequest,
                )
            }
        }
    }

    /**
     * A page of an extension that may not run in private tabs, loaded in one: the engine left the tab black while the
     * address bar named the extension. The load is refused and the user told why.
     */
    private fun blockedInPrivateTab(engineSession: EngineSession, uri: String): Boolean {
        if (!uri.startsWith("moz-extension://")) return false
        if (context.components.core.store.state.findTabOrCustomTab(engineSession)?.content?.private != true)
            return false
        val extension =
            WebExtensionSupport.installedExtensions.values.firstOrNull { extension ->
                extension.getMetadata()?.baseUrl?.let(uri::startsWith) == true
            } ?: return false
        if (extension.isAllowedInPrivateBrowsing()) return false
        val name = extension.getMetadata()?.name?.takeIf { it.isNotBlank() } ?: extension.id
        Toast.makeText(context, context.getString(R.string.archwave_extension_not_in_private, name), Toast.LENGTH_LONG)
            .show()
        return true
    }

    override fun onErrorRequest(
        session: EngineSession,
        errorType: ErrorType,
        uri: String?,
    ): RequestInterceptor.ErrorResponse {
        val errorPage = ErrorPages.createUrlEncodedErrorPage(context, errorType, uri)
        return RequestInterceptor.ErrorResponse(errorPage)
    }

    override fun interceptsAppInitiatedRequests() = true
}
