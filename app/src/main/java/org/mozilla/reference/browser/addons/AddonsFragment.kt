/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.addons

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.CancellableOperation
import mozilla.components.feature.addons.Addon
import mozilla.components.feature.addons.AddonManagerException
import mozilla.components.feature.addons.ui.AddonFilePicker
import mozilla.components.feature.addons.ui.AddonsManagerAdapter
import mozilla.components.feature.addons.ui.AddonsManagerAdapterDelegate
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.ext.components

private const val FIND_MORE_URL = "https://addons.mozilla.org/android/"

/** Fragment use for managing add-ons. */
class AddonsFragment : Fragment(), AddonsManagerAdapterDelegate, MenuProvider {
    private val logger = Logger("AddonsFragment")
    private val webExtensionPromptFeature = ViewBoundFeatureWrapper<WebExtensionPromptFeature>()
    private var adapter: AddonsManagerAdapter? = null
    private var loadJob: Job? = null

    private val installState by lazy { ViewModelProvider(this)[InstallState::class.java] }
    private lateinit var addonFilePicker: AddonFilePicker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The picker must register for its result before the fragment is started. Gecko checks the signature, and the
        // prompt feature below shows the permission, success and failure dialogs of file installs too.
        addonFilePicker =
            AddonFilePicker(requireContext(), requireContext().components.core.addonManager).apply {
                registerForResults(this@AddonsFragment)
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_add_ons, container, false)

    override fun onViewCreated(
        rootView: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(rootView, savedInstanceState)
        webExtensionPromptFeature.set(
            feature =
                WebExtensionPromptFeature(
                    store = requireContext().components.core.store,
                    context = requireContext(),
                    fragmentManager = parentFragmentManager,
                ),
            owner = this,
            view = rootView,
        )
        // One layout manager for the fragment's life, so the scroll position survives reloads of the list.
        rootView.findViewById<RecyclerView>(R.id.add_ons_list).layoutManager = LinearLayoutManager(requireContext())
        rootView.findViewById<View>(R.id.add_ons_retry).setOnClickListener { bindRecyclerView() }
        val cancelInstall = rootView.findViewById<View>(R.id.cancel_install)
        cancelInstall.setOnClickListener {
            // The install still ends in onSuccess (the cancel came too late) or onError, which hides the overlay.
            installState.operation.value?.cancel()
            cancelInstall.isEnabled = false
        }
        viewLifecycleOwner.lifecycleScope.launch {
            installState.operation.collect {
                rootView.findViewById<View>(R.id.addonProgressOverlay).isVisible = it != null
                cancelInstall.isEnabled = true
            }
        }
        requireActivity().addMenuProvider(this, viewLifecycleOwner)

        // Installs from a file or from the addons.mozilla.org page finish outside this screen's callbacks.
        viewLifecycleOwner.lifecycleScope.launch {
            requireContext()
                .components
                .core
                .store
                .flow(viewLifecycleOwner)
                .map { it.extensions.keys }
                .distinctUntilChanged()
                .drop(1)
                .collect { bindRecyclerView() }
        }
    }

    override fun onStart() {
        super.onStart()
        bindRecyclerView()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        adapter = null
    }

    private fun bindRecyclerView() {
        val rootView = view ?: return
        val recyclerView = rootView.findViewById<RecyclerView>(R.id.add_ons_list)
        val loading = rootView.findViewById<View>(R.id.add_ons_loading)
        val loadError = rootView.findViewById<View>(R.id.add_ons_load_error)
        val addonManager = requireContext().components.core.addonManager
        loading.isVisible = adapter == null
        loadJob?.cancel()
        loadJob =
            viewLifecycleOwner.lifecycleScope.launch {
                val addons =
                    try {
                        addonManager.getAddons()
                    } catch (e: AddonManagerException) {
                        // A newer load cancelled this one (the manager wraps the cancellation): leave the views to it.
                        ensureActive()
                        logger.error("Failed to load add-ons", e)
                        null
                    }
                loading.isVisible = false
                // The manager hides catalogue errors and returns the installed add-ons alone.
                // shortcut: also shown if every recommended add-on is installed; fine while the collection is large.
                loadError.isVisible = addons == null || addons.none { !it.isInstalled() }
                if (addons == null) return@launch
                val listed = withCatalogueData(addons)

                // Updating the existing adapter, instead of a new one, keeps the scroll position.
                adapter?.updateAddons(listed)
                    ?: AddonsManagerAdapter(
                            this@AddonsFragment,
                            listed,
                            // Names in white, everything else in the secondary grey the list uses by default.
                            style = AddonsManagerAdapter.Style(addonNameTextColor = R.color.aw_text_1),
                            store = requireContext().components.core.store,
                        )
                        .also {
                            adapter = it
                            recyclerView.adapter = it
                            it.registerAdapterDataObserver(keepTopInViewOnInsert(recyclerView))
                        }
            }
    }

    /**
     * Installed add-ons are built from the extension alone, so they show 0 stars, "Reviews: 0" and no description. Give
     * those in the recommended collection their catalogue rating and description. Only when the catalogue just loaded,
     * so its cache is fresh and this does not go to the network again.
     */
    @Suppress("TooGenericExceptionCaught") // Without the catalogue data the list is still right, just plainer.
    private suspend fun withCatalogueData(addons: List<Addon>): List<Addon> {
        if (addons.none { !it.isInstalled() } || addons.none { it.isInstalled() }) return addons
        val catalogue =
            try {
                requireContext().components.core.addonProvider.getFeaturedAddons(allowCache = true).associateBy {
                    it.id
                }
            } catch (e: Exception) {
                logger.warn("Could not read the catalogue for installed add-ons", e)
                return addons
            }
        return addons.map { addon ->
            val listed = catalogue[addon.id]?.takeIf { addon.isInstalled() } ?: return@map addon
            addon.copy(
                rating = listed.rating,
                translatableDescription = listed.translatableDescription,
                ratingUrl = listed.ratingUrl,
                detailUrl = listed.detailUrl,
            )
        }
    }

    /** A just installed add-on is added at the top; keep it in view if the list was scrolled to the top. */
    private fun keepTopInViewOnInsert(recyclerView: RecyclerView) =
        object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                if (positionStart == 0 && layoutManager.findFirstCompletelyVisibleItemPosition() == 0) {
                    recyclerView.scrollToPosition(0)
                }
            }
        }

    override fun onAddonItemClicked(addon: Addon) {
        if (addon.isInstalled()) {
            val intent = Intent(context, InstalledAddonDetailsActivity::class.java)
            intent.putExtra("add_on", addon)
            startActivity(intent)
        } else {
            val intent = Intent(context, AddonDetailsActivity::class.java)
            intent.putExtra("add_on", addon)
            startActivity(intent)
        }
    }

    override fun onInstallAddonButtonClicked(addon: Addon) {
        val state = installState
        if (state.operation.value != null) {
            return
        }
        var operation: CancellableOperation? = null
        operation =
            requireContext()
                .components
                .core
                .addonManager
                .installAddon(
                    url = addon.downloadUrl,
                    // The list follows the store's extensions, so only the overlay is left to update here.
                    onSuccess = { state.operation.compareAndSet(operation, null) },
                    onError = { _ -> state.operation.compareAndSet(operation, null) },
                )
        state.operation.value = operation
    }

    override fun shouldShowFindMoreAddonsButton() = true

    override fun onFindMoreAddonsButtonClicked() = requireContext().openInBrowserTab(FIND_MORE_URL)

    override fun onCreateMenu(
        menu: Menu,
        menuInflater: MenuInflater,
    ) {
        menu.add(R.string.archwave_addons_install_from_file).setOnMenuItemClickListener {
            if (!addonFilePicker.launch()) {
                Toast.makeText(requireContext(), R.string.archwave_addons_no_file_picker, Toast.LENGTH_LONG).show()
            }
            true
        }
    }

    override fun onMenuItemSelected(menuItem: MenuItem) = false
}

/**
 * The install started from the manager, if any. It outlives a rotation, and an earlier install's late callback (after a
 * cancel that came too late) cannot end the one running now. It can be cancelled until the engine asks for consent.
 */
class InstallState : ViewModel() {
    /** The running install operation. */
    val operation = MutableStateFlow<CancellableOperation?>(null)
}
