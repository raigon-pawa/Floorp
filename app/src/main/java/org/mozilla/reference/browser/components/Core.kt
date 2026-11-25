/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.components

import android.content.Context
import android.graphics.Color
import android.os.Environment
import androidx.preference.PreferenceManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import mozilla.components.browser.engine.gecko.permission.GeckoSitePermissionsStorage
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.session.storage.SessionStorage
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.browser.storage.sync.RemoteTabsStorage
import mozilla.components.browser.thumbnails.ThumbnailsMiddleware
import mozilla.components.browser.thumbnails.storage.ThumbnailStorage
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.DownloadDelegate
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy
import mozilla.components.concept.engine.mediaquery.PreferredColorScheme
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.addons.AddonManager
import mozilla.components.feature.addons.amo.AMOAddonsProvider
import mozilla.components.feature.addons.migration.DefaultSupportedAddonsChecker
import mozilla.components.feature.addons.update.DefaultAddonUpdater
import mozilla.components.feature.customtabs.store.CustomTabsServiceStore
import mozilla.components.feature.downloads.DownloadMiddleware
import mozilla.components.feature.media.MediaSessionFeature
import mozilla.components.feature.media.middleware.RecordingDevicesMiddleware
import mozilla.components.feature.prompts.file.FileUploadsDirCleaner
import mozilla.components.feature.pwa.ManifestStorage
import mozilla.components.feature.pwa.WebAppShortcutManager
import mozilla.components.feature.readerview.ReaderViewMiddleware
import mozilla.components.feature.search.middleware.SearchMiddleware
import mozilla.components.feature.search.region.RegionMiddleware
import mozilla.components.feature.session.HistoryDelegate
import mozilla.components.feature.session.middleware.undo.UndoMiddleware
import mozilla.components.feature.sitepermissions.OnDiskSitePermissionsStorage
import mozilla.components.feature.webnotifications.WebNotificationFeature
import mozilla.components.lib.crash.CrashReporter
import mozilla.components.lib.dataprotect.SecureAbove22Preferences
import mozilla.components.service.location.LocationService
import mozilla.components.service.sync.logins.SyncableLoginsStorage
import mozilla.components.support.base.worker.Frequency
import mozilla.components.support.utils.DefaultDownloadFileUtils
import org.mozilla.reference.browser.AppRequestInterceptor
import org.mozilla.reference.browser.BrowserActivity
import org.mozilla.reference.browser.EngineProvider
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.R.string.pref_key_remote_debugging
import org.mozilla.reference.browser.browser.CrashedTabMiddleware
import org.mozilla.reference.browser.browser.KeepTitleMiddleware
import org.mozilla.reference.browser.browser.ReaderSecurityMiddleware
import org.mozilla.reference.browser.browser.TrackingProtectionStateMiddleware
import org.mozilla.reference.browser.downloads.DownloadService
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.ext.getPreferenceKey
import org.mozilla.reference.browser.media.MediaSessionService
import org.mozilla.reference.browser.settings.Settings

private const val DAY_IN_MINUTES = 24 * 60L

/** Component group for all core browser functionality. */
class Core(
    private val context: Context,
    crashReporter: CrashReporter,
    private val applicationScope: CoroutineScope,
) {
    /** The browser engine component initialized based on the build configuration (see build variants). */
    val engine: Engine by lazy {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val dohProviderUrl = Settings.getDohProviderUrl(context)

        val defaultSettings =
            DefaultSettings(
                requestInterceptor = AppRequestInterceptor(context),
                remoteDebuggingEnabled = prefs.getBoolean(context.getPreferenceKey(pref_key_remote_debugging), false),
                testingModeEnabled = prefs.getBoolean(context.getPreferenceKey(R.string.pref_key_testing_mode), false),
                trackingProtectionPolicy = createTrackingProtectionPolicy(),
                historyTrackingDelegate = HistoryDelegate(lazyHistoryStorage),
                globalPrivacyControlEnabled = Settings.isGlobalPrivacyControl(context),
                httpsOnlyMode = Settings.httpsOnlyModeFor(Settings.isHttpsOnly(context)),
                dohSettingsMode = Settings.dohModeFor(dohProviderUrl),
                dohProviderUrl = dohProviderUrl,
                // Used by Gecko whenever the resolver URL is empty; its own default is mozilla.cloudflare-dns.com.
                dohDefaultProviderUrl = context.getString(R.string.doh_provider_quad9),
                emailTrackerBlockingPrivateBrowsing = true,
                preferredColorScheme = PreferredColorScheme.Dark,
                // Black instead of the default white, so blank tabs and page loads do not flash.
                clearColor = Color.BLACK,
                // Without it the download prompt named the file from its URL alone, while the download service
                // used the server's Content-Disposition: the prompt showed another name than the file got.
                downloadDelegate =
                    object : DownloadDelegate {
                        override fun guessFileName(contentDisposition: String?, url: String?, mimeType: String?) =
                            downloadFileUtils.guessFileName(contentDisposition, url, mimeType)
                    },
            )
        EngineProvider.createEngine(context, defaultSettings).also {
            // GeckoEngine does not copy these from DefaultSettings, so they must be set on the live settings.
            it.settings.queryParameterStripping = true
            it.settings.queryParameterStrippingPrivateBrowsing = true
        }
    }

    /** The [Client] implementation (`concept-fetch`) used for HTTP requests. */
    val client: Client by lazy {
        EngineProvider.createClient(context)
    }

    private val downloadFileUtils by lazy {
        DefaultDownloadFileUtils(
            context = context,
            downloadLocation = {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
            },
        )
    }

    /** The [BrowserStore] holds the global [BrowserState]. */
    val store by lazy {
        BrowserStore(
                middleware =
                    listOf(
                        DownloadMiddleware(
                            applicationContext = context,
                            downloadServiceClass = DownloadService::class.java,
                            deleteFileFromStorage = { false },
                            downloadFileUtils = downloadFileUtils,
                        ),
                        // Private browsing leaves nothing on disk, so no pictures of private tabs: the storage would
                        // keep them until the next start. The tabs tray shows a placeholder for them.
                        { store, next, action ->
                            val privateThumbnail =
                                action is ContentAction.UpdateThumbnailAction &&
                                    store.state.findTab(action.sessionId)?.content?.private == true
                            if (!privateThumbnail) next(action)
                        },
                        ThumbnailsMiddleware(thumbnailStorage),
                        ReaderViewMiddleware(),
                        ReaderSecurityMiddleware(),
                        RegionMiddleware(
                            context,
                            LocationService.default(),
                            applicationScope = applicationScope,
                        ),
                        SearchMiddleware(context),
                        RecordingDevicesMiddleware(context, context.components.notificationsDelegate),
                        CrashedTabMiddleware(),
                        TrackingProtectionStateMiddleware { engine.settings.trackingProtectionPolicy },
                        KeepTitleMiddleware(),
                        // Keeps closed tabs for a few seconds, for the tabs tray's Undo.
                        UndoMiddleware(),
                    ) + EngineMiddleware.create(engine)
            )
            .apply {
                icons.install(engine, this)

                WebNotificationFeature(
                    context,
                    engine,
                    icons,
                    R.drawable.ic_notification,
                    geckoSitePermissionsStorage,
                    BrowserActivity::class.java,
                    notificationsDelegate = context.components.notificationsDelegate,
                )

                MediaSessionFeature(context, MediaSessionService::class.java, this).start()
            }
    }

    /** The [CustomTabsServiceStore] holds global custom tabs related data. */
    val customTabsStore by lazy { CustomTabsServiceStore() }

    /** The storage component for persisting browser tab sessions. */
    val sessionStorage: SessionStorage by lazy {
        SessionStorage(context, engine, applicationScope = applicationScope)
    }

    /** The storage component to persist browsing history (with the exception of private sessions). */
    val lazyHistoryStorage = lazy { PlacesHistoryStorage(context) }

    /** A convenience accessor to the [PlacesHistoryStorage]. */
    val historyStorage by lazy { lazyHistoryStorage.value }

    /** The storage component to persist logins data (username/password) for websites. */
    val lazyLoginsStorage = lazy { SyncableLoginsStorage(context, lazySecurePrefs) }

    /** A convenience accessor to the [SyncableLoginsStorage]. */
    val loginsStorage by lazy { lazyLoginsStorage.value }

    /** The storage component to sync and persist tabs in a Firefox Sync account. */
    val lazyRemoteTabsStorage = lazy { RemoteTabsStorage(context, crashReporter) }

    /** A storage component for persisting thumbnail images of tabs. */
    val thumbnailStorage by lazy { ThumbnailStorage(context) }

    /** Component for managing shortcuts (both regular and PWA). */
    val shortcutManager by lazy { WebAppShortcutManager(context, client, ManifestStorage(context)) }

    /** A storage component for site permissions. */
    val geckoSitePermissionsStorage by lazy {
        val geckoRuntime = EngineProvider.getOrCreateRuntime(context)
        GeckoSitePermissionsStorage(
            geckoRuntime,
            OnDiskSitePermissionsStorage(context, coroutineScope = applicationScope),
        )
    }

    /** Icons component for loading, caching and processing website icons. */
    val icons by lazy { BrowserIcons(context, client) }

    // Addons
    val addonManager by lazy {
        AddonManager(store, engine, addonProvider, addonUpdater)
    }

    val addonUpdater by lazy {
        DefaultAddonUpdater(
            context,
            Frequency(1, TimeUnit.DAYS),
            notificationsDelegate = context.components.notificationsDelegate,
        )
    }

    val addonProvider by lazy {
        if (Settings.isAmoCollectionOverrideConfigured(context)) {
            provideCustomAddonProvider()
        } else {
            provideDefaultAddonProvider()
        }
    }

    val supportedAddonsChecker by lazy {
        DefaultSupportedAddonsChecker(
            context,
            Frequency(12, TimeUnit.HOURS),
        )
    }

    val fileUploadsDirCleaner: FileUploadsDirCleaner by lazy {
        FileUploadsDirCleaner(scope = applicationScope) { context.cacheDir }
    }

    private fun provideDefaultAddonProvider(): AMOAddonsProvider =
        AMOAddonsProvider(
            context = context,
            client = client,
            collectionName = "7dfae8669acc4312a65e8ba5553036",
            maxCacheAgeInMinutes = DAY_IN_MINUTES,
        )

    private fun provideCustomAddonProvider(): AMOAddonsProvider =
        AMOAddonsProvider(
            context,
            client,
            collectionUser = Settings.getOverrideAmoUser(context),
            collectionName = Settings.getOverrideAmoCollection(context),
            maxCacheAgeInMinutes = DAY_IN_MINUTES,
        )

    /**
     * Constructs a [TrackingProtectionPolicy] based on current preferences.
     *
     * @param normalMode whether or not tracking protection should be enabled in normal browsing mode, defaults to the
     *   current preference value.
     * @param privateMode whether or not tracking protection should be enabled in private browsing mode, default to the
     *   current preference value.
     * @return the constructed tracking protection policy based on preferences.
     */
    fun createTrackingProtectionPolicy(
        normalMode: Boolean = Settings.isTrackingProtectionNormal(context),
        privateMode: Boolean = Settings.isTrackingProtectionPrivate(context),
    ): TrackingProtectionPolicy = trackingProtectionPolicyFor(normalMode, privateMode)

    private val lazySecurePrefs = lazy { SecureAbove22Preferences(context, KEY_STORAGE_NAME) }

    companion object {
        private const val KEY_STORAGE_NAME = "core_prefs"

        /** The tracking protection policy for the two Settings switches. */
        fun trackingProtectionPolicyFor(normalMode: Boolean, privateMode: Boolean): TrackingProtectionPolicy {
            val trackingPolicy = TrackingProtectionPolicy.strict()
            return when {
                normalMode && privateMode -> trackingPolicy
                // Without strict social blocking, as Android Components does for private-only: Gecko applies it to
                // every
                // tab, private ones included. Social trackers stay blocked in normal tabs through the tracking list.
                normalMode && !privateMode ->
                    TrackingProtectionPolicy.select(
                            trackingCategories = trackingPolicy.trackingCategories,
                            cookiePolicy = trackingPolicy.cookiePolicy,
                            strictSocialTrackingProtection = false,
                            cookiePurging = trackingPolicy.cookiePurging,
                            bounceTrackingProtectionMode = trackingPolicy.bounceTrackingProtectionMode,
                            allowListBaselineTrackingProtection = trackingPolicy.allowListBaselineTrackingProtection,
                            allowListConvenienceTrackingProtection =
                                trackingPolicy.allowListConvenienceTrackingProtection,
                            fingerprintingProtection = trackingPolicy.fingerprintingProtection,
                            fingerprintingProtectionPrivateBrowsing =
                                trackingPolicy.fingerprintingProtectionPrivateBrowsing,
                        )
                        .forRegularSessionsOnly()
                !normalMode && privateMode -> trackingPolicy.forPrivateSessionsOnly()
                else -> TrackingProtectionPolicy.none()
            }
        }
    }
}
