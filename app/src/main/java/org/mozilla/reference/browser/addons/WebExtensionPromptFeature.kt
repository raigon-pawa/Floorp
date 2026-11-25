/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.addons

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.widget.Button
import android.widget.CompoundButton
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.browser.state.action.WebExtensionAction
import mozilla.components.browser.state.state.extension.WebExtensionPromptRequest
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.webextension.PermissionPromptResponse
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.concept.engine.webextension.WebExtensionInstallException
import mozilla.components.feature.addons.Addon
import mozilla.components.feature.addons.AddonManager
import mozilla.components.feature.addons.R as addonsR
import mozilla.components.feature.addons.ui.AddonInstallationDialogFragment
import mozilla.components.feature.addons.ui.PermissionsDialogFragment
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.ext.components

/** Feature implementation for handling [WebExtensionPromptRequest] and showing the respective UI. */
class WebExtensionPromptFeature(
    private val store: BrowserStore,
    private val context: Context,
    private val fragmentManager: FragmentManager,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : LifecycleAwareFeature {
    /** Whether or not an add-on installation is in progress. */
    private var isInstallationInProgress = false
    private var scope: CoroutineScope? = null

    /**
     * The library fills both permission buttons with the primary colour; Cancel is the safe choice, so tone it down. It
     * also ticks the technical-and-interaction data box ("opt-out"); this build shares no data unless the user opts in,
     * so untick it once per dialog, leaving a choice restored after a rotation alone. Likewise tick the private
     * browsing box once for an extension that already runs in private tabs.
     */
    private val denyButtonStyler =
        object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                val dialog = (f as? PermissionsDialogFragment)?.dialog ?: return
                dialog.findViewById<Button>(addonsR.id.deny_button)?.run {
                    backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.aw_surface_3))
                    setTextColor(context.getColor(R.color.aw_text_1))
                }
                val arguments = f.arguments ?: return
                if (!arguments.getBoolean(KEY_DATA_COLLECTION_DEFAULT_APPLIED)) {
                    dialog.findViewById<CompoundButton>(addonsR.id.technical_and_interaction_data)?.isChecked = false
                    if (arguments.getBoolean(KEY_PRIVATE_BROWSING_DEFAULT)) {
                        dialog.findViewById<CompoundButton>(addonsR.id.allow_in_private_browsing)?.isChecked = true
                    }
                    arguments.putBoolean(KEY_DATA_COLLECTION_DEFAULT_APPLIED, true)
                }
            }
        }

    /** Starts observing the selected session to listen for window requests and opens / closes tabs as needed. */
    override fun start() {
        fragmentManager.registerFragmentLifecycleCallbacks(denyButtonStyler, false)
        scope =
            store.flowScoped(dispatcher = mainDispatcher) { flow ->
                flow
                    .mapNotNull { state ->
                        state.webExtensionPromptRequest
                    }
                    .distinctUntilChanged()
                    .collect { promptRequest ->
                        if (promptRequest is WebExtensionPromptRequest.AfterInstallation) {
                            handleAfterInstallationRequest(promptRequest)
                        }

                        if (promptRequest is WebExtensionPromptRequest.BeforeInstallation.InstallationFailed) {
                            handleBeforeInstallationRequest(promptRequest)
                            consumePromptRequest()
                        }
                    }
            }
        tryToReAttachButtonHandlersToPreviousDialog()
    }

    private suspend fun handleAfterInstallationRequest(promptRequest: WebExtensionPromptRequest.AfterInstallation) {
        // The install flow in Fenix relies on an [Addon] object so let's convert the (GeckoView)
        // extension into a minimal add-on. The missing metadata will be fetched when the user
        // opens the add-ons manager. The icon is read from the package (quick), else the sheets show a generic one.
        val addon = Addon.newFromWebExtension(promptRequest.extension).copy(icon = loadIcon(promptRequest.extension))
        when (promptRequest) {
            is WebExtensionPromptRequest.AfterInstallation.Permissions.Required ->
                handlePermissionRequest(
                    addon,
                    promptRequest,
                )

            is WebExtensionPromptRequest.AfterInstallation.PostInstallation -> handlePostInstallationRequest(addon)

            is WebExtensionPromptRequest.AfterInstallation.Permissions.Optional ->
                handleOptionalPermissionsRequest(
                    addon,
                    promptRequest,
                )
        }
    }

    @Suppress("TooGenericExceptionCaught") // A missing icon must not stop the prompt; the generic one is the fallback.
    private suspend fun loadIcon(extension: WebExtension): Bitmap? =
        try {
            withTimeoutOrNull(ICON_TIMEOUT_MS) { extension.loadIcon(AddonManager.ADDON_ICON_SIZE) }
        } catch (e: Exception) {
            // stop() cancelled the scope: no prompt may be shown now, the next start() shows it again.
            currentCoroutineContext().ensureActive()
            Logger.warn("Could not load the icon of ${extension.id}", e)
            null
        }

    private fun handlePostInstallationRequest(addon: Addon) {
        // Installs from the add-ons manager register themselves; web and file installs would wait for the next start.
        // The registration is idempotent.
        context.components.core.addonUpdater.registerForFutureUpdates(addon.id)
        // The engine's handler for runtime.openOptionsPage() can be left holding the metadata from before the options
        // page URL was known, so the request is dropped until the next start. Listing re-registers it with fresh data.
        context.components.core.engine.listInstalledWebExtensions(onSuccess = {}, onError = {})
        showPostInstallationDialog(addon)
    }

    private fun handlePermissionRequest(
        addon: Addon,
        promptRequest: WebExtensionPromptRequest.AfterInstallation.Permissions.Required,
    ) {
        if (hasExistingPermissionDialogFragment()) return
        showPermissionDialog(
            addon = addon,
            promptRequest = promptRequest,
            permissions = promptRequest.permissions,
            origins = promptRequest.origins,
            dataCollectionPermissions = promptRequest.dataCollectionPermissions,
        )
    }

    private fun handleOptionalPermissionsRequest(
        addon: Addon,
        promptRequest: WebExtensionPromptRequest.AfterInstallation.Permissions.Optional,
    ) {
        val shouldGrantWithoutPrompt =
            canGrantOptionalPermissionsWithoutPrompt(
                localizedPermissions = Addon.localizePermissions(promptRequest.permissions, context),
                origins = promptRequest.origins,
                dataCollectionPermissions = promptRequest.dataCollectionPermissions,
            )

        // If we don't have any promptable permissions, just proceed.
        if (shouldGrantWithoutPrompt) {
            promptRequest.onConfirm(true)
            consumePromptRequest()
            return
        }

        showPermissionDialog(
            // This is a bit of a hack so that the permission prompt only lists
            // the optional permissions that are requested.
            addon = addon.copy(permissions = promptRequest.permissions),
            promptRequest = promptRequest,
            permissions = promptRequest.permissions,
            origins = promptRequest.origins,
            dataCollectionPermissions = promptRequest.dataCollectionPermissions,
            forOptionalPermissions = true,
        )
    }

    private fun showPostInstallationDialog(addon: Addon) {
        if (!isInstallationInProgress && !hasExistingAddonPostInstallationDialogFragment()) {
            val dialog = attachCallbacks(AddonInstallationDialogFragment.newInstance(addon = addon))
            dialog.show(fragmentManager, POST_INSTALLATION_DIALOG_FRAGMENT_TAG)
        }
    }

    // The fragment does not save its callbacks: set them on a new sheet and again on one restored after a rotation.
    private fun attachCallbacks(dialog: AddonInstallationDialogFragment): AddonInstallationDialogFragment {
        dialog.onDismissed = { consumePromptRequest() }
        dialog.onConfirmButtonClicked = { _ -> consumePromptRequest() }
        dialog.onExtensionSettingsLinkClicked = { installed ->
            consumePromptRequest()
            context.startActivity(
                Intent(context, InstalledAddonDetailsActivity::class.java)
                    .putExtra("add_on", installed)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return dialog
    }

    /** Stops observing the selected session for incoming window requests. */
    override fun stop() {
        scope?.cancel()
        fragmentManager.unregisterFragmentLifecycleCallbacks(denyButtonStyler)
    }

    @VisibleForTesting
    internal fun showPermissionDialog(
        addon: Addon,
        promptRequest: WebExtensionPromptRequest.AfterInstallation.Permissions,
        permissions: List<String> = emptyList(),
        origins: List<String> = emptyList(),
        dataCollectionPermissions: List<String> = emptyList(),
        forOptionalPermissions: Boolean = false,
    ) {
        if (!isInstallationInProgress && !hasExistingPermissionDialogFragment()) {
            val dialog =
                PermissionsDialogFragment.newInstance(
                    addon = addon,
                    permissions = permissions,
                    origins = origins,
                    dataCollectionPermissions = dataCollectionPermissions,
                    forOptionalPermissions = forOptionalPermissions,
                    onPositiveButtonClicked = { _, privateBrowsingAllowed, technicalDataAllowed ->
                        handlePermissions(
                            promptRequest,
                            granted = true,
                            privateBrowsingAllowed = privateBrowsingAllowed,
                            technicalDataAllowed = technicalDataAllowed,
                        )
                    },
                    onNegativeButtonClicked = {
                        when (promptRequest) {
                            is WebExtensionPromptRequest.AfterInstallation.Permissions.Optional -> {
                                promptRequest.onConfirm(false)
                            }

                            is WebExtensionPromptRequest.AfterInstallation.Permissions.Required -> {
                                promptRequest.onConfirm(PermissionPromptResponse(isPermissionsGranted = false))
                            }
                        }
                        consumePromptRequest()
                    },
                    onLearnMoreClicked = ::openLearnMore,
                )
            // Reinstalling or updating an extension that may run in private tabs: the box starts unticked and Add
            // would silently revoke that, so start it ticked.
            if (!forOptionalPermissions && store.state.extensions[addon.id]?.allowedInPrivateBrowsing == true) {
                dialog.arguments?.putBoolean(KEY_PRIVATE_BROWSING_DEFAULT, true)
            }
            dialog.show(
                fragmentManager,
                PERMISSIONS_DIALOG_FRAGMENT_TAG,
            )
        }
    }

    private fun tryToReAttachButtonHandlersToPreviousDialog() {
        // A prompt restored after the process died has no request behind it any more (the engine forgot the install),
        // so its buttons could do nothing: close it.
        val request = store.state.webExtensionPromptRequest
        findPostInstallationDialogFragment()?.let { dialog ->
            if (request is WebExtensionPromptRequest.AfterInstallation.PostInstallation) {
                attachCallbacks(dialog)
            } else {
                dialog.dismiss()
            }
        }
        findPreviousDialogFragment()?.let { dialog ->
            if (request !is WebExtensionPromptRequest.AfterInstallation.Permissions) {
                dialog.dismiss()
                return
            }
            dialog.onPositiveButtonClicked = { addon, privateBrowsingAllowed, technicalDataAllowed ->
                store.state.webExtensionPromptRequest?.let { promptRequest ->
                    if (
                        promptRequest is WebExtensionPromptRequest.AfterInstallation.Permissions &&
                            addon.id == promptRequest.extension.id
                    ) {
                        handlePermissions(
                            promptRequest,
                            granted = true,
                            privateBrowsingAllowed = privateBrowsingAllowed,
                            technicalDataAllowed = technicalDataAllowed,
                        )
                    }
                }
            }
            dialog.onLearnMoreClicked = ::openLearnMore
            dialog.onNegativeButtonClicked = {
                store.state.webExtensionPromptRequest?.let { promptRequest ->
                    handlePermissions(
                        promptRequest,
                        granted = false,
                        privateBrowsingAllowed = false,
                        technicalDataAllowed = false,
                    )
                }
            }
        }
    }

    // The prompt stays up behind the new tab, so the user can come back and answer it.
    private fun openLearnMore() = context.openInBrowserTab(PERMISSIONS_LEARN_MORE_URL)

    private fun handlePermissions(
        promptRequest: WebExtensionPromptRequest,
        granted: Boolean,
        privateBrowsingAllowed: Boolean,
        technicalDataAllowed: Boolean,
    ) {
        when (promptRequest) {
            is WebExtensionPromptRequest.AfterInstallation.Permissions.Optional -> {
                promptRequest.onConfirm(granted)
            }

            is WebExtensionPromptRequest.AfterInstallation.Permissions.Required -> {
                val response =
                    PermissionPromptResponse(
                        isPermissionsGranted = granted,
                        isPrivateModeGranted = privateBrowsingAllowed,
                        isTechnicalAndInteractionDataGranted = technicalDataAllowed,
                    )
                promptRequest.onConfirm(response)
            }

            is WebExtensionPromptRequest.InstallationRequested -> {
                // opt-out
            }

            is WebExtensionPromptRequest.AfterInstallation.PostInstallation -> {
                // opt-out
            }

            is WebExtensionPromptRequest.BeforeInstallation.InstallationFailed -> {
                // opt-out
            }
        }
        consumePromptRequest()
    }

    private fun consumePromptRequest() {
        store.dispatch(WebExtensionAction.ConsumePromptRequestWebExtensionAction)
    }

    private fun hasExistingPermissionDialogFragment(): Boolean = findPreviousDialogFragment() != null

    private fun findPreviousDialogFragment(): PermissionsDialogFragment? =
        fragmentManager.findFragmentByTag(PERMISSIONS_DIALOG_FRAGMENT_TAG) as? PermissionsDialogFragment

    private fun hasExistingAddonPostInstallationDialogFragment(): Boolean = findPostInstallationDialogFragment() != null

    private fun findPostInstallationDialogFragment(): AddonInstallationDialogFragment? =
        fragmentManager.findFragmentByTag(POST_INSTALLATION_DIALOG_FRAGMENT_TAG) as? AddonInstallationDialogFragment

    private fun handleBeforeInstallationRequest(promptRequest: WebExtensionPromptRequest.BeforeInstallation) {
        when (promptRequest) {
            is WebExtensionPromptRequest.BeforeInstallation.InstallationFailed -> {
                handleInstallationFailedRequest(exception = promptRequest.exception)
                consumePromptRequest()
            }
        }
    }

    @VisibleForTesting
    internal fun handleInstallationFailedRequest(exception: WebExtensionInstallException) {
        val addonName = exception.extensionName ?: ""
        val appName = context.getString(R.string.app_name)
        var title = context.getString(addonsR.string.mozac_feature_addons_cant_install_extension, "")
        val message =
            when (exception) {
                is WebExtensionInstallException.Blocklisted -> {
                    context.getString(addonsR.string.mozac_feature_addons_blocklisted_2, addonName, appName)
                }

                is WebExtensionInstallException.SoftBlocked -> {
                    context.getString(addonsR.string.mozac_feature_addons_soft_blocked_2, addonName, appName)
                }

                is WebExtensionInstallException.UserCancelled -> {
                    // We don't want to show an error message when users cancel installation.
                    return
                }

                is WebExtensionInstallException.UnsupportedAddonType,
                is WebExtensionInstallException.Unknown -> {
                    // Making sure we don't have a
                    // Title = Can't install extension
                    // Message = Failed to install $addonName
                    title = ""
                    if (addonName.isNotEmpty()) {
                        context.getString(addonsR.string.mozac_feature_addons_failed_to_install, addonName)
                    } else {
                        context.getString(addonsR.string.mozac_feature_addons_extension_failed_to_install)
                    }
                }

                is WebExtensionInstallException.NetworkFailure -> {
                    context.getString(addonsR.string.mozac_feature_addons_extension_failed_to_install_network_error)
                }

                is WebExtensionInstallException.CorruptFile -> {
                    context.getString(addonsR.string.mozac_feature_addons_extension_failed_to_install_corrupt_error)
                }

                is WebExtensionInstallException.NotSigned -> {
                    context.getString(addonsR.string.mozac_feature_addons_extension_failed_to_install_not_signed_error)
                }

                is WebExtensionInstallException.Incompatible -> {
                    // The extension's minimum and maximum versions are Gecko versions, not the app's.
                    context.getString(
                        addonsR.string.mozac_feature_addons_failed_to_install_incompatible_error,
                        addonName,
                        appName,
                        context.getString(R.string.archwave_addons_engine_version, MOZ_APP_VERSION),
                    )
                }

                is WebExtensionInstallException.AdminInstallOnly -> {
                    context.getString(addonsR.string.mozac_feature_addons_admin_install_only, addonName)
                }
            }

        showDialog(
            title = title,
            message = message,
        )
    }

    @VisibleForTesting
    internal fun showDialog(
        title: String,
        message: String,
    ) {
        // A fragment, so the message survives a rotation.
        MessageDialogFragment.newInstance(title, message).show(fragmentManager, INSTALL_ERROR_DIALOG_FRAGMENT_TAG)
    }

    companion object {
        private const val PERMISSIONS_DIALOG_FRAGMENT_TAG = "ADDONS_PERMISSIONS_DIALOG_FRAGMENT"
        private const val POST_INSTALLATION_DIALOG_FRAGMENT_TAG = "ADDONS_INSTALLATION_DIALOG_FRAGMENT"
        private const val INSTALL_ERROR_DIALOG_FRAGMENT_TAG = "ARCHWAVE_INSTALL_ERROR_DIALOG_FRAGMENT"
        private const val KEY_DATA_COLLECTION_DEFAULT_APPLIED = "archwave_data_collection_default_applied"
        private const val ICON_TIMEOUT_MS = 1000L
        private const val KEY_PRIVATE_BROWSING_DEFAULT = "archwave_private_browsing_default"
    }
}

/**
 * Whether an extension's runtime request for optional permissions may be granted without asking the user. Site access
 * ([origins]) and [dataCollectionPermissions] always need consent, even when no API permission has a message to show
 * ([localizedPermissions] empty).
 */
internal fun canGrantOptionalPermissionsWithoutPrompt(
    localizedPermissions: List<String>,
    origins: List<String>,
    dataCollectionPermissions: List<String>,
): Boolean = localizedPermissions.isEmpty() && origins.isEmpty() && dataCollectionPermissions.isEmpty()
