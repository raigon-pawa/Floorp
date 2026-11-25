/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.addons

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.CustomTabListAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.WebExtensionAction
import mozilla.components.browser.state.selector.findCustomTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.createCustomTab
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.engine.window.WindowRequest
import mozilla.components.feature.prompts.PromptFeature
import mozilla.components.lib.state.ext.consumeFrom
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.ktx.android.view.setupPersistentInsets
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.ext.components

/** An activity to show the pop up action of a web extension. */
class WebExtensionActionPopupActivity : AppCompatActivity() {
    private lateinit var webExtensionId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_add_on_settings)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        window.setupPersistentInsets()

        webExtensionId = requireNotNull(intent.getStringExtra("web_extension_id"))
        intent.getStringExtra("web_extension_name")?.let {
            title = it
        }

        // After a rotation the restored fragment still holds the popup; a new one would find no session.
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.addonSettingsContainer, WebExtensionActionPopupFragment.create(webExtensionId))
                .commit()
        }
    }

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet,
    ): View? =
        when (name) {
            EngineView::class.java.name -> {
                components.core.engine.createView(context, attrs).asView()
            }

            else -> {
                super.onCreateView(parent, name, context, attrs)
            }
        }

    /**
     * A fragment to show the web extension action popup with [EngineView].
     *
     * While the popup is open its engine session belongs to a custom tab in the store, so the prompt feature can show
     * the page's select boxes, dialogs and file pickers. Removing that tab closes the session.
     */
    class WebExtensionActionPopupFragment : Fragment(), EngineSession.Observer {
        private var engineSession: EngineSession? = null
        private lateinit var webExtensionId: String
        private val promptFeature = ViewBoundFeatureWrapper<PromptFeature>()
        private val webExtensionPromptFeature = ViewBoundFeatureWrapper<WebExtensionPromptFeature>()
        private lateinit var requestPromptsPermissionsLauncher: ActivityResultLauncher<Array<String>>

        private val addonSettingsEngineView: EngineView
            get() = requireView().findViewById<View>(R.id.addonSettingsEngineView) as EngineView

        private val popupTabId: String?
            get() = arguments?.getString(KEY_POPUP_TAB_ID)

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            requestPromptsPermissionsLauncher =
                registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                    val grantResults =
                        results.values
                            .map { if (it) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED }
                            .toIntArray()
                    promptFeature.withFeature { it.onPermissionsResult(results.keys.toTypedArray(), grantResults) }
                }
        }

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View? {
            webExtensionId = requireNotNull(arguments?.getString("web_extension_id"))
            val state = requireContext().components.core.store.state
            engineSession =
                popupTabId?.let { state.findCustomTab(it)?.engineState?.engineSession }
                    ?: state.extensions[webExtensionId]?.popupSession

            return inflater.inflate(R.layout.fragment_add_on_settings, container, false)
        }

        override fun onViewCreated(
            view: View,
            savedInstanceState: Bundle?,
        ) {
            super.onViewCreated(view, savedInstanceState)
            // permissions.request() from the popup must be answered here, not after the user went back to the browser.
            webExtensionPromptFeature.set(
                feature =
                    WebExtensionPromptFeature(
                        store = requireContext().components.core.store,
                        context = requireContext(),
                        fragmentManager = parentFragmentManager,
                    ),
                owner = viewLifecycleOwner,
                view = view,
            )

            val session = engineSession
            if (session != null) {
                showPopup(session)
            } else {
                consumeFrom(requireContext().components.core.store) { state ->
                    state.extensions[webExtensionId]?.let { extState ->
                        extState.popupSession?.let {
                            if (engineSession == null) {
                                engineSession = it
                                showPopup(it)
                                it.register(this)
                            }
                        }
                    }
                }
            }

            // An extension that opens or selects a tab from its popup wants the user to see that tab.
            viewLifecycleOwner.lifecycleScope.launch {
                requireContext()
                    .components
                    .core
                    .store
                    .flow(viewLifecycleOwner)
                    .map { it.selectedTabId }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { activity?.finish() }
            }
        }

        private fun showPopup(session: EngineSession) {
            val store = requireContext().components.core.store
            addonSettingsEngineView.render(session)
            if (popupTabId == null) {
                consumePopupSession()
                val tab =
                    createCustomTab(
                        url = "",
                        source = SessionState.Source.Internal.None,
                        private = store.state.selectedTab?.content?.private == true,
                    )
                store.dispatch(CustomTabListAction.AddCustomTabAction(tab))
                store.dispatch(EngineAction.LinkEngineSessionAction(tab.id, session, skipLoading = true))
                requireArguments().putString(KEY_POPUP_TAB_ID, tab.id)
            }
            promptFeature.set(
                feature =
                    PromptFeature(
                        fragment = this,
                        store = store,
                        customTabId = popupTabId,
                        fragmentManager = parentFragmentManager,
                        tabsUseCases = requireContext().components.useCases.tabsUseCases,
                        fileUploadsDirCleaner = requireContext().components.core.fileUploadsDirCleaner,
                        onNeedToRequestPermissions = { requestPromptsPermissionsLauncher.launch(it) },
                    ),
                owner = viewLifecycleOwner,
                view = requireView(),
            )
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // The prompt feature still uses startActivityForResult.
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            if (!promptFeature.onActivityResult(requestCode, data, resultCode)) {
                super.onActivityResult(requestCode, resultCode, data)
            }
        }

        override fun onStart() {
            super.onStart()
            engineSession?.register(this)
        }

        override fun onStop() {
            super.onStop()
            engineSession?.unregister(this)
        }

        override fun onDestroy() {
            super.onDestroy()
            // Each click on the action creates a new session: close this one unless the popup is only rotating.
            if (activity?.isChangingConfigurations != true) {
                popupTabId?.let {
                    requireContext().components.core.store.dispatch(CustomTabListAction.RemoveCustomTabAction(it))
                }
            }
        }

        override fun onWindowRequest(windowRequest: WindowRequest) {
            when (windowRequest.type) {
                WindowRequest.Type.CLOSE -> activity?.onBackPressedDispatcher?.onBackPressed()
                // A link that opens a new window becomes a tab, which also closes the popup (see onViewCreated).
                WindowRequest.Type.OPEN -> {
                    val store = requireContext().components.core.store
                    requireContext()
                        .components
                        .useCases
                        .tabsUseCases
                        .addTab(
                            selectTab = true,
                            parentId = store.state.selectedTabId,
                            engineSession = windowRequest.prepare(),
                            private = store.state.selectedTab?.content?.private == true,
                        )
                    windowRequest.start()
                }
            }
        }

        private fun consumePopupSession() {
            requireContext()
                .components
                .core
                .store
                .dispatch(WebExtensionAction.UpdatePopupSessionAction(webExtensionId, popupSession = null))
        }

        companion object {
            private const val KEY_POPUP_TAB_ID = "popup_tab_id"

            /** Create an [WebExtensionActionPopupFragment] with webExtensionId as a required parameter. */
            fun create(webExtensionId: String) =
                WebExtensionActionPopupFragment().apply {
                    arguments =
                        Bundle().apply {
                            putString("web_extension_id", webExtensionId)
                        }
                }
        }
    }
}
