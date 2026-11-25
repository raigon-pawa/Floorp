/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.SearchAction
import mozilla.components.browser.state.action.SystemAction
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.concept.engine.webextension.isUnsupported
import mozilla.components.feature.addons.update.GlobalAddonDependencyProvider
import mozilla.components.support.AppServicesInitializer
import mozilla.components.support.AppServicesInitializer.Config as AppServicesConfig
import mozilla.components.support.base.log.Log
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.base.log.sink.AndroidLogSink
import mozilla.components.support.ktx.android.content.isMainProcess
import mozilla.components.support.ktx.android.content.runOnlyInMainProcess
import mozilla.components.support.rusthttp.RustHttpConfig
import mozilla.components.support.webextensions.WebExtensionSupport

/**
 * Owns the app's [Components]. Engine and add-on setup run only in the main process, never in GeckoView child or crash
 * handler processes.
 */
open class BrowserApplication : Application() {
    /**
     * Scope for long-running work that must outlive any individual activity. [SupervisorJob] keeps a failure in one
     * child from cancelling the others.
     */
    private val applicationScope: CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Main +
                CoroutineExceptionHandler { _, throwable ->
                    Logger.error("ApplicationScope: Unhandled error: ${throwable.message}", throwable)
                }
        )

    val components by lazy { Components(this, applicationScope) }

    override fun onCreate() {
        super.onCreate()

        // Always dark, whatever the device setting.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        // GeckoView takes the accent colour for web content from the application context's theme.
        setTheme(R.style.ArchwaveEngineColors)

        setupCrashReporting(this)

        AppServicesInitializer.init(AppServicesConfig(components.analytics.crashReporter))
        RustHttpConfig.setClient(lazy { components.core.client })

        // Android Components log visited URLs at debug and info level (icons, incoming links, picked files), private
        // tabs included; a release build must not leave them in logcat.
        if (!BuildConfig.DEBUG) Log.logLevel = Log.Priority.WARN
        Log.addSink(AndroidLogSink())

        if (!isMainProcess()) {
            // If this is not the main process then do not continue with the initialization here. Everything that
            // follows only needs to be done in our app's main process and should not be done in other processes like
            // a GeckoView child process or the crash handling process. Most importantly we never want to end up in a
            // situation where we create a GeckoRuntime from the Gecko child process (
            return
        }

        components.core.engine.warmUp()

        restoreBrowserState()
        // Nothing in Android Components dispatches this, and without it no search engine is ever loaded.
        components.core.store.dispatch(SearchAction.RefreshSearchEnginesAction)

        GlobalAddonDependencyProvider.initialize(
            components.core.addonManager,
            components.core.addonUpdater,
        )
        WebExtensionSupport.initialize(
            runtime = components.core.engine,
            store = components.core.store,
            // Decides whether popups and extension tabs open as private, and keeps extensions not allowed in private
            // browsing out of private tabs.
            isInPrivateBrowsingMode = { components.core.store.state.selectedTab?.content?.private == true },
            onNewTabOverride = { _, engineSession, url, selected, isPrivate ->
                val tabId =
                    components.useCases.tabsUseCases.addTab(
                        url = url,
                        selectTab = selected,
                        engineSession = engineSession,
                        private = isPrivate,
                    )
                tabId
            },
            onCloseTabOverride = { _, sessionId ->
                components.useCases.tabsUseCases.removeTab(sessionId)
            },
            onSelectTabOverride = { _, sessionId ->
                components.useCases.tabsUseCases.selectTab(sessionId)
            },
            onExtensionsLoaded = { extensions ->
                components.core.addonUpdater.registerForFutureUpdates(extensions)

                val checker = components.core.supportedAddonsChecker
                val hasUnsupportedAddons = extensions.any { it.isUnsupported() }
                if (hasUnsupportedAddons) {
                    checker.registerForChecks()
                } else {
                    // As checks are a persistent subscriptions, we have to make sure
                    // we remove any previous subscriptions.
                    checker.unregisterForChecks()
                }
            },
            onUpdatePermissionRequest = components.core.addonUpdater::onUpdatePermissionRequest,
        )

        applicationScope.launch(Dispatchers.IO) {
            components.core.fileUploadsDirCleaner.cleanUploadsDirectory()
            deleteMinidumps()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runOnlyInMainProcess {
            components.core.store.dispatch(SystemAction.LowMemoryAction(level))
            components.core.icons.onTrimMemory(level)
        }
    }

    private fun restoreBrowserState() = applicationScope.launch {
        val store = components.core.store
        val sessionStorage = components.core.sessionStorage

        components.useCases.tabsUseCases.restore(sessionStorage)

        // Now that we have restored our previous state (if there's one) let's setup auto saving the state while
        // the app is used.
        sessionStorage
            .autoSave(store)
            .periodicallyInForeground(interval = 30, unit = TimeUnit.SECONDS)
            .whenGoingToBackground()
            .whenSessionsChange()
    }
}

/**
 * Crashes are recorded in the crash database and never sent, so the memory images Gecko writes for them (private tabs
 * included) would only pile up: delete those left by earlier runs.
 */
private fun BrowserApplication.deleteMinidumps() {
    val mozilla = filesDir.resolve("mozilla")
    val dirs =
        listOf(mozilla.resolve("Crash Reports/pending"), filesDir.resolve("minidumps")) +
            mozilla.listFiles().orEmpty().map { it.resolve("minidumps") }
    dirs
        .flatMap { it.listFiles().orEmpty().asList() }
        .filter { it.isFile && (it.extension == "dmp" || it.extension == "extra") }
        .forEach { it.delete() }
}

private fun setupCrashReporting(application: BrowserApplication) {
    application.components.analytics.crashReporter.install(application)
}
