/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.content.Context
import java.io.File
import java.io.IOException
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.engine.gecko.fetch.GeckoViewFetchClient
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.webcompat.WebCompatFeature
import mozilla.components.lib.crash.handler.CrashHandlerService
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.reference.browser.settings.Settings

/**
 * Holds the process-wide [GeckoRuntime]. GeckoView allows only one runtime per process, so the engine, the fetch client
 * and site permission storage must all share it.
 */
object EngineProvider {
    private var runtime: GeckoRuntime? = null

    /** Synchronized so that concurrent callers cannot create a second [GeckoRuntime]. */
    @Synchronized
    fun getOrCreateRuntime(context: Context): GeckoRuntime {
        if (runtime == null) {
            val builder = GeckoRuntimeSettings.Builder()

            builder.crashHandler(CrashHandlerService::class.java)

            // About config it's no longer enabled by default
            builder.aboutConfigEnabled(true)
            builder.extensionsWebAPIEnabled(true)
            // Off by default in GeckoView 160: extensions would run in the main process, where an extension's crash
            // takes the whole app down. In their own process a crash only stops extensions, and the engine offers a
            // restart after repeated crashes (the prompt in BrowserActivity).
            builder.extensionsProcessEnabled(true)
            copyConfigFile(context)?.let { builder.configFilePath(it.absolutePath) }
            // GeckoEngine applies the DoH mode before the resolver URL, after Gecko has started; with no URL yet, Gecko
            // would briefly use its built-in resolver (mozilla.cloudflare-dns.com). Start with the chosen one instead.
            Settings.getDohProviderUrl(context)
                .takeIf { it.isNotEmpty() }
                ?.let { builder.trustedRecursiveResolverUri(it) }
            runtime = GeckoRuntime.create(context, builder.build())
        }

        return runtime!!
    }

    /**
     * GeckoView reads its start-up prefs (privacy prefs that have no typed setting) from a real file, so the bundled
     * asset is copied on every start; app updates refresh it. Written to a temporary file and renamed so a crash
     * mid-copy cannot leave a truncated file behind. If the copy fails, a previous copy is still used.
     */
    private fun copyConfigFile(context: Context): File? {
        val target = File(context.filesDir, CONFIG_FILE)
        try {
            val temp = File(context.filesDir, "$CONFIG_FILE.tmp")
            context.assets.open(CONFIG_FILE).use { input -> temp.outputStream().use(input::copyTo) }
            if (!temp.renameTo(target)) throw IOException("Cannot rename $temp")
        } catch (e: IOException) {
            Logger.error("Could not copy the GeckoView config file", e)
        }
        return target.takeIf { it.exists() }
    }

    private const val CONFIG_FILE = "archwave-geckoview-config.yaml"

    /** Creates the engine on the shared runtime and installs [WebCompatFeature] on it. */
    fun createEngine(
        context: Context,
        defaultSettings: DefaultSettings,
    ): Engine {
        val runtime = getOrCreateRuntime(context)

        return GeckoEngine(context, defaultSettings, runtime).also {
            WebCompatFeature.install(it)
        }
    }

    /** Backed by the shared runtime, so requests made through this client use Gecko's network stack. */
    fun createClient(context: Context): Client {
        val runtime = getOrCreateRuntime(context)
        return GeckoViewFetchClient(context, runtime)
    }
}
