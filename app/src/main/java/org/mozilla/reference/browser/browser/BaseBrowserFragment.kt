/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.CallSuper
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.Snackbar.LENGTH_LONG
import mozilla.components.browser.state.selector.findTabOrCustomTabOrSelectedTab
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.toolbar.BrowserToolbar
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.app.links.AppLinksFeature
import mozilla.components.feature.downloads.DownloadDialogFragment
import mozilla.components.feature.downloads.DownloadsFeature
import mozilla.components.feature.downloads.R as DownloadsR
import mozilla.components.feature.downloads.manager.FetchDownloadManager
import mozilla.components.feature.downloads.temporary.ShareResourceFeature
import mozilla.components.feature.findinpage.view.FindInPageBar
import mozilla.components.feature.findinpage.view.FindInPageView
import mozilla.components.feature.media.fullscreen.MediaSessionFullscreenFeature
import mozilla.components.feature.prompts.PromptFeature
import mozilla.components.feature.session.FullScreenFeature
import mozilla.components.feature.session.ScreenOrientationFeature
import mozilla.components.feature.session.SessionFeature
import mozilla.components.feature.session.SwipeRefreshFeature
import mozilla.components.feature.sitepermissions.SitePermissionsFeature
import mozilla.components.feature.sitepermissions.SitePermissionsRules
import mozilla.components.feature.sitepermissions.SitePermissionsRules.Action.ASK_TO_ALLOW
import mozilla.components.feature.sitepermissions.SitePermissionsRules.AutoplayAction
import mozilla.components.feature.tabs.WindowFeature
import mozilla.components.feature.webauthn.WebAuthnFeature
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.base.feature.ActivityResultHandler
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.android.view.enterImmersiveMode
import mozilla.components.support.ktx.android.view.exitImmersiveMode
import mozilla.components.support.utils.DefaultDownloadFileUtils
import mozilla.components.ui.widgets.behavior.DependencyGravity
import mozilla.components.ui.widgets.behavior.EngineViewClippingBehavior
import mozilla.components.ui.widgets.behavior.EngineViewScrollingGesturesBehavior
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoView
import org.mozilla.reference.browser.BuildConfig
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.addons.WebExtensionPromptFeature
import org.mozilla.reference.browser.downloads.AskFirstDownloadManager
import org.mozilla.reference.browser.downloads.DownloadService
import org.mozilla.reference.browser.ext.getPreferenceKey
import org.mozilla.reference.browser.ext.requireComponents
import org.mozilla.reference.browser.pip.PictureInPictureIntegration
import org.mozilla.reference.browser.tabs.LastTabFeature

/**
 * Base fragment extended by [BrowserFragment] and [ExternalAppBrowserFragment]. This class only contains shared code
 * focused on the main browsing content. UI code specific to the app or to custom tabs can be found in the subclasses.
 */
abstract class BaseBrowserFragment : Fragment(), UserInteractionHandler, ActivityResultHandler {
    private val sessionFeature = ViewBoundFeatureWrapper<SessionFeature>()
    private val toolbarIntegration = ViewBoundFeatureWrapper<ToolbarIntegration>()
    private val contextMenuIntegration = ViewBoundFeatureWrapper<ContextMenuIntegration>()
    private val downloadsFeature = ViewBoundFeatureWrapper<DownloadsFeature>()
    private val shareResourceFeature = ViewBoundFeatureWrapper<ShareResourceFeature>()
    private val appLinksFeature = ViewBoundFeatureWrapper<AppLinksFeature>()
    private val promptsFeature = ViewBoundFeatureWrapper<PromptFeature>()
    private val webExtensionPromptFeature = ViewBoundFeatureWrapper<WebExtensionPromptFeature>()
    private val fullScreenFeature = ViewBoundFeatureWrapper<FullScreenFeature>()
    private val findInPageIntegration = ViewBoundFeatureWrapper<FindInPageIntegration>()
    private val sitePermissionFeature = ViewBoundFeatureWrapper<SitePermissionsFeature>()
    private val pictureInPictureIntegration = ViewBoundFeatureWrapper<PictureInPictureIntegration>()
    private var leftPictureInPicture = false
    private val swipeRefreshFeature = ViewBoundFeatureWrapper<SwipeRefreshFeature>()
    private val windowFeature = ViewBoundFeatureWrapper<WindowFeature>()
    private val webAuthnFeature = ViewBoundFeatureWrapper<WebAuthnFeature>()
    private val fullScreenMediaSessionFeature = ViewBoundFeatureWrapper<MediaSessionFullscreenFeature>()
    private val lastTabFeature = ViewBoundFeatureWrapper<LastTabFeature>()
    private val screenOrientationFeature = ViewBoundFeatureWrapper<ScreenOrientationFeature>()

    private val engineView: EngineView
        get() = requireView().findViewById<View>(R.id.engineView) as EngineView

    private val toolbar: BrowserToolbar
        get() = requireView().findViewById(R.id.toolbar)

    private val findInPageBar: FindInPageBar
        get() = requireView().findViewById(R.id.findInPageBar)

    private val swipeRefresh: SwipeRefreshLayout
        get() = requireView().findViewById(R.id.swipeRefresh)

    private val backButtonHandler: List<ViewBoundFeatureWrapper<*>> =
        listOf(
            fullScreenFeature,
            findInPageIntegration,
            toolbarIntegration,
            sessionFeature,
            lastTabFeature,
        )

    private val activityResultHandler: List<ViewBoundFeatureWrapper<*>> =
        listOf(
            webAuthnFeature,
            promptsFeature,
        )

    protected val sessionId: String?
        get() = arguments?.getString(SESSION_ID)

    protected var webAppToolbarShouldBeVisible = true

    private lateinit var requestDownloadPermissionsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var requestSitePermissionsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var requestPromptsPermissionsLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestDownloadPermissionsLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                val permissions = results.keys.toTypedArray()
                val grantResults =
                    results.values
                        .map {
                            if (it) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                        }
                        .toIntArray()
                downloadsFeature.withFeature {
                    it.onPermissionsResult(permissions, grantResults)
                }
            }

        requestSitePermissionsLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                val permissions = results.keys.toTypedArray()
                val grantResults =
                    results.values
                        .map {
                            if (it) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                        }
                        .toIntArray()
                sitePermissionFeature.withFeature {
                    it.onPermissionsResult(permissions, grantResults)
                }
            }

        requestPromptsPermissionsLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                val permissions = results.keys.toTypedArray()
                val grantResults =
                    results.values
                        .map {
                            if (it) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                        }
                        .toIntArray()
                val documentPickerAllowed = FilePickerPermissions.canOpenDocumentPicker(requireContext())
                promptsFeature.withFeature {
                    it.onPermissionsResult(
                        permissions,
                        FilePickerPermissions.grantResults(grantResults, documentPickerAllowed),
                    )
                }
            }
    }

    final override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_browser, container, false)

    @CallSuper
    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        setupSessionAndToolbar(view)
        setupDownloads(view)
        setupLinksAndPrompts(view)
        setupPageFeatures(view)

        // The page is laid out for the toolbar shown; as the toolbar scrolls away the clipping moves fixed-bottom
        // page elements down with it. With zero heights both were no-ops and such elements sat in the wrong place.
        val toolbarHeight = resources.getDimensionPixelSize(R.dimen.browser_toolbar_height)
        (swipeRefresh.layoutParams as? CoordinatorLayout.LayoutParams)?.apply {
            behavior =
                EngineViewClippingBehavior(
                    context = requireContext(),
                    attrs = null,
                    engineViewParent = swipeRefresh,
                    topToolbarHeight = 0,
                    bottomToolbarHeight = toolbarHeight,
                )
        }
        engineView.setDynamicToolbarMaxHeight(toolbarHeight)
        swipeRefreshFeature.bind(
            view,
            SwipeRefreshFeature(
                requireComponents.core.store,
                requireComponents.useCases.sessionUseCases.reload,
                swipeRefresh,
            ),
        )

        lastTabFeature.bind(
            view,
            LastTabFeature(
                requireComponents.core.store,
                sessionId,
                requireComponents.useCases.tabsUseCases.removeTab,
                requireActivity()::finish,
            ),
        )

        screenOrientationFeature.bind(
            view,
            ScreenOrientationFeature(
                requireComponents.core.engine,
                requireActivity(),
            ),
        )

        if (BuildConfig.MOZILLA_OFFICIAL) {
            webAuthnFeature.bind(
                view,
                WebAuthnFeature(
                    requireComponents.core.engine,
                    requireActivity(),
                    requireComponents.useCases.sessionUseCases.exitFullscreen::invoke,
                ) {
                    requireComponents.core.store.state.selectedTabId
                },
            )
        }
    }

    private fun setupSessionAndToolbar(view: View) {
        sessionFeature.bind(
            view,
            SessionFeature(
                requireComponents.core.store,
                requireComponents.useCases.sessionUseCases.goBack,
                requireComponents.useCases.sessionUseCases.goForward,
                engineView,
                sessionId,
            ),
        )

        (toolbar.layoutParams as? CoordinatorLayout.LayoutParams)?.apply {
            behavior =
                EngineViewScrollingGesturesBehavior(
                    engineView = engineView,
                    dependency = toolbar,
                    dependencyGravity = DependencyGravity.Bottom,
                )
        }

        toolbarIntegration.bind(
            view,
            ToolbarIntegration(
                requireContext(),
                toolbar,
                view,
                requireComponents.core.historyStorage,
                requireComponents.core.store,
                requireComponents.useCases.sessionUseCases,
                requireComponents.useCases.tabsUseCases,
                requireComponents.useCases.webAppUseCases,
                sessionId,
            ),
        )

        contextMenuIntegration.bind(
            view,
            ContextMenuIntegration(
                requireContext(),
                parentFragmentManager,
                requireComponents.core.store,
                requireComponents.useCases.tabsUseCases,
                requireComponents.useCases.contextMenuUseCases,
                engineView,
                view,
                sessionId,
            ),
        )
    }

    private fun setupDownloads(view: View) {
        shareResourceFeature.bind(
            view,
            ShareResourceFeature(
                context = requireContext().applicationContext,
                httpClient = requireComponents.core.client,
                store = requireComponents.core.store,
                tabId = sessionId,
            ),
        )

        val downloadFileUtils =
            DefaultDownloadFileUtils(
                context = requireContext().applicationContext,
                downloadLocation = {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
                },
            )
        downloadsFeature.bind(
            view,
            DownloadsFeature(
                requireContext(),
                store = requireComponents.core.store,
                useCases = requireComponents.useCases.downloadsUseCases,
                tabId = sessionId,
                fragmentManager = childFragmentManager,
                downloadFileUtils = downloadFileUtils,
                downloadManager =
                    AskFirstDownloadManager(
                        FetchDownloadManager(
                            requireContext().applicationContext,
                            requireComponents.core.store,
                            DownloadService::class,
                            notificationsDelegate =
                                NotificationsDelegate(NotificationManagerCompat.from(requireContext())),
                        ),
                        requireComponents.notificationsDelegate,
                        ::shouldShowRequestPermissionRationale,
                    ),
                onNeedToRequestPermissions = { permissions ->
                    requestDownloadPermissionsLauncher.launch(permissions)
                },
                // A full-width panel at the bottom, like the add-on prompts, instead of a floating black box.
                promptsStyling =
                    DownloadsFeature.PromptsStyling(gravity = Gravity.BOTTOM, shouldWidthMatchParent = true),
                onDownloadStopped = { download, _, status ->
                    // Without notifications nothing else says the file arrived or lets the user open it.
                    if (
                        status == DownloadState.Status.COMPLETED &&
                            view.isAttachedToWindow &&
                            !NotificationManagerCompat.from(view.context).areNotificationsEnabled()
                    ) {
                        showDownloadCompleted(view, download, downloadFileUtils)
                    }
                },
            ),
        )
        // After the process died the dialog comes back but the tab's download request does not, so Download would do
        // nothing. While the request is there (a rotation), the feature reattaches the dialog to it.
        if (requireComponents.core.store.state.findTabOrCustomTabOrSelectedTab(sessionId)?.content?.download == null) {
            (childFragmentManager.findFragmentByTag(DownloadDialogFragment.FRAGMENT_TAG) as? DialogFragment)?.dismiss()
        }
    }

    private fun showDownloadCompleted(view: View, download: DownloadState, fileUtils: DefaultDownloadFileUtils) {
        val context = view.context
        Snackbar.make(view, context.getString(R.string.archwave_download_completed, download.fileName), LENGTH_LONG)
            .setAnchorView(toolbar.takeIf { it.isVisible })
            .withoutSystemBarMargins()
            .setAction(DownloadsR.string.mozac_feature_downloads_button_open) {
                if (!fileUtils.openFile(download.fileName, download.directoryPath, download.contentType)) {
                    val extension = MimeTypeMap.getFileExtensionFromUrl(download.filePath)
                    val message =
                        context.getString(DownloadsR.string.mozac_feature_downloads_open_not_supported1, extension)
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun setupLinksAndPrompts(view: View) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        appLinksFeature.bind(
            view,
            AppLinksFeature(
                requireContext(),
                store = requireComponents.core.store,
                sessionId = sessionId,
                fragmentManager = parentFragmentManager,
                dialog = ::cancelableAppLinkDialog,
                launchInApp = {
                    prefs.getBoolean(
                        requireContext().getPreferenceKey(R.string.pref_key_launch_external_app),
                        false,
                    )
                },
            ),
        )

        promptsFeature.bind(
            view,
            PromptFeature(
                fragment = this,
                store = requireComponents.core.store,
                tabsUseCases = requireComponents.useCases.tabsUseCases,
                customTabId = sessionId,
                fileUploadsDirCleaner = requireComponents.core.fileUploadsDirCleaner,
                fragmentManager = parentFragmentManager,
                onNeedToRequestPermissions = { permissions ->
                    val store = requireComponents.core.store
                    if (
                        FilePickerPermissions.isPlainFileInput(store.state, sessionId) &&
                            FilePickerPermissions.canOpenDocumentPicker(requireContext())
                    ) {
                        // Open the document picker straight away rather than asking for camera, mic and media.
                        promptsFeature.withFeature {
                            it.onPermissionsResult(
                                permissions,
                                FilePickerPermissions.grantResults(IntArray(permissions.size), true),
                            )
                        }
                    } else {
                        requestPromptsPermissionsLauncher.launch(permissions)
                    }
                },
            ),
        )

        webExtensionPromptFeature.bind(
            view,
            WebExtensionPromptFeature(
                store = requireComponents.core.store,
                context = requireContext(),
                fragmentManager = parentFragmentManager,
            ),
        )

        windowFeature.bind(
            view,
            WindowFeature(requireComponents.core.store, requireComponents.useCases.tabsUseCases),
        )
    }

    private fun setupPageFeatures(view: View) {
        fullScreenFeature.bind(
            view,
            FullScreenFeature(
                store = requireComponents.core.store,
                sessionUseCases = requireComponents.useCases.sessionUseCases,
                tabId = sessionId,
                viewportFitChanged = ::viewportFitChanged,
                fullScreenChanged = ::fullScreenChanged,
            ),
        )

        findInPageIntegration.bind(
            view,
            FindInPageIntegration(
                requireComponents.core.store,
                sessionId,
                findInPageBar as FindInPageView,
                engineView,
            ),
        )

        sitePermissionFeature.bind(
            view,
            SitePermissionsFeature(
                context = requireContext(),
                fragmentManager = parentFragmentManager,
                sessionId = sessionId,
                storage = requireComponents.core.geckoSitePermissionsStorage,
                onNeedToRequestPermissions = { permissions ->
                    requestSitePermissionsLauncher.launch(permissions)
                },
                onShouldShowRequestPermissionRationale = { shouldShowRequestPermissionRationale(it) },
                // Every category still asks, as without rules; audible media only plays after a tap.
                sitePermissionsRules =
                    SitePermissionsRules(
                        camera = ASK_TO_ALLOW,
                        location = ASK_TO_ALLOW,
                        notification = ASK_TO_ALLOW,
                        microphone = ASK_TO_ALLOW,
                        autoplayAudible = AutoplayAction.BLOCKED,
                        autoplayInaudible = AutoplayAction.ALLOWED,
                        persistentStorage = ASK_TO_ALLOW,
                        mediaKeySystemAccess = ASK_TO_ALLOW,
                        crossOriginStorageAccess = ASK_TO_ALLOW,
                        localDeviceAccess = ASK_TO_ALLOW,
                        localNetworkAccess = ASK_TO_ALLOW,
                    ),
                promptsStyling =
                    SitePermissionsFeature.PromptsStyling(gravity = Gravity.BOTTOM, shouldWidthMatchParent = true),
                store = requireComponents.core.store,
                coroutineScope = requireComponents.applicationScope,
            ),
        )

        pictureInPictureIntegration.bind(
            view,
            PictureInPictureIntegration(
                requireComponents.core.store,
                requireActivity(),
                sessionId,
            ),
        )

        fullScreenMediaSessionFeature.bind(
            view,
            MediaSessionFullscreenFeature(
                requireActivity(),
                requireComponents.core.store,
                sessionId,
            ),
        )
    }

    private fun fullScreenChanged(enabled: Boolean) {
        if (enabled) {
            activity?.enterImmersiveMode()
            // The clipping behaviour still reads a hidden toolbar's translation on every layout pass: one left
            // scrolled away would clip the fullscreen page by the toolbar height.
            toolbar.translationY = 0f
            toolbar.visibility = View.GONE
            engineView.setDynamicToolbarMaxHeight(0)
        } else {
            activity?.exitImmersiveMode()
            toolbar.visibility = View.VISIBLE
            engineView.setDynamicToolbarMaxHeight(resources.getDimensionPixelSize(R.dimen.browser_toolbar_height))
        }
    }

    private fun viewportFitChanged(viewportFit: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requireActivity().window.attributes.layoutInDisplayCutoutMode = viewportFit
        }
    }

    @CallSuper
    override fun onBackPressed(): Boolean = clearTextSelection() || backButtonHandler.any { it.onBackPressed() }

    /** Back first clears a text selection and its action bar, as elsewhere in Android, instead of going back. */
    private fun clearTextSelection(): Boolean {
        // shortcut: GeckoEngineView (Android Components 160) holds its GeckoView as child 0 and does not say whether
        // there is a selection; if an upgrade moves it, Back goes back with a selection again.
        val geckoView = (engineView as? ViewGroup)?.getChildAt(0) as? GeckoView
        // GeckoView's own delegate: the app sets none, so the engine view's clearSelection() has nothing to clear.
        val selection = geckoView?.session?.selectionActionDelegate as? BasicSelectionActionDelegate
        if (selection?.selection == null) return false
        selection.clearSelection()
        return true
    }

    final override fun onHomePressed(): Boolean = pictureInPictureIntegration.get()?.onHomePressed() ?: false

    final override fun onPictureInPictureModeChanged(enabled: Boolean) {
        pictureInPictureIntegration.get()?.onPictureInPictureModeChanged(enabled)
        if (enabled) return
        // Expanding the window resumes the activity and closing it stops it; which one comes is known only then.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            leftPictureInPicture = true
        } else {
            onPictureInPictureClosed()
        }
    }

    override fun onResume() {
        super.onResume()
        // Expanded: the fullscreen video the user left is still fullscreen.
        leftPictureInPicture = false
    }

    override fun onStop() {
        super.onStop()
        if (leftPictureInPicture) onPictureInPictureClosed()
    }

    // Closing the window means stop watching, as in Chrome and Firefox: pause, and leave fullscreen.
    private fun onPictureInPictureClosed() {
        leftPictureInPicture = false
        val session = requireComponents.core.store.state.findTabOrCustomTabOrSelectedTab(sessionId) ?: return
        session.mediaSessionState?.controller?.pause()
        if (session.content.fullScreen) {
            onBackPressed()
            fullScreenChanged(false)
        }
    }

    protected fun <T : LifecycleAwareFeature> ViewBoundFeatureWrapper<T>.bind(
        view: View,
        feature: T,
    ) = set(feature, this@BaseBrowserFragment, view)

    companion object {
        private const val SESSION_ID = "session_id"

        @JvmStatic
        protected fun Bundle.putSessionId(sessionId: String?) {
            putString(SESSION_ID, sessionId)
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        data: Intent?,
        resultCode: Int,
    ): Boolean {
        Logger.info(
            "Fragment onActivityResult received with " +
                "requestCode: $requestCode, resultCode: $resultCode, data: $data"
        )

        return activityResultHandler.any { it.onActivityResult(requestCode, data, resultCode) }
    }

    // PromptFeature starts the file picker from this fragment, so its result comes back here with the feature's own
    // request code; the activity only sees the code AndroidX remapped it to.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!onActivityResult(requestCode, data, resultCode)) {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}
