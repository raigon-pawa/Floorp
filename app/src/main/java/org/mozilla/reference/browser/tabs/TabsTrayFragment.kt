/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.tabs

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.tabstray.DefaultTabViewHolder
import mozilla.components.browser.tabstray.TabViewHolder
import mozilla.components.browser.tabstray.TabsAdapter
import mozilla.components.browser.tabstray.TabsTray
import mozilla.components.browser.tabstray.TabsTrayStyling
import mozilla.components.browser.tabstray.ViewHolderProvider
import mozilla.components.browser.thumbnails.loader.ThumbnailLoader
import mozilla.components.concept.base.images.ImageLoadRequest
import mozilla.components.concept.base.images.ImageLoader
import mozilla.components.feature.tabs.tabstray.TabsFeature
import mozilla.components.support.base.feature.UserInteractionHandler
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.browser.BrowserFragment
import org.mozilla.reference.browser.browser.StartPage
import org.mozilla.reference.browser.browser.UrlDisplay
import org.mozilla.reference.browser.browser.withoutSystemBarMargins
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.ext.requireComponents

/** A fragment for displaying the tabs tray. */
class TabsTrayFragment : Fragment(), UserInteractionHandler {
    private var tabsFeature: TabsFeature? = null

    // Whether the panel shown has any tab. Switching to an empty panel is not closing its last tab.
    private var showingTabs = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_tabstray, container, false)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        val trayAdapter = createAndSetupTabsTray(requireContext())

        tabsFeature =
            TabsFeature(
                trayAdapter,
                requireComponents.core.store,
                // The library also calls this on the first update after a switch to an empty panel.
                onCloseTray = { if (showingTabs) closeTabsTray() },
            ) {
                !it.content.private
            }

        val tabsPanel: TabsPanel = view.findViewById(R.id.tabsPanel)
        val tabsToolbar: TabsToolbar = view.findViewById(R.id.tabsToolbar)

        tabsPanel.initialize(tabsFeature, updateTabsToolbar = ::updateTabsToolbar)
        tabsToolbar.initialize(tabsFeature, closeTabsTray = ::closeTabsTray, closeTabs = ::closeTabs)
        // Open on the panel of the tab the user came from.
        if (requireComponents.core.store.state.selectedTab?.content?.private == true) tabsPanel.selectPrivate()
    }

    override fun onStart() {
        super.onStart()

        tabsFeature?.start()
    }

    override fun onStop() {
        super.onStop()

        tabsFeature?.stop()
    }

    override fun onBackPressed(): Boolean {
        closeTabsTray()
        return true
    }

    private fun closeTabsTray() {
        activity?.supportFragmentManager?.beginTransaction()?.apply {
            replace(R.id.container, BrowserFragment.create())
            commit()
        }
    }

    /**
     * Closes tabs with [close] and offers to bring them back for as long as the store keeps them. The snackbar sits on
     * the activity, so it stays when closing the last tab of a panel takes the tray away.
     */
    private fun closeTabs(@StringRes message: Int, close: () -> Unit) {
        val activity = activity ?: return
        val store = activity.components.core.store
        val tabsUseCases = activity.components.useCases.tabsUseCases
        val before = store.state.tabs.map { it.id }.toSet()
        close()
        val content = activity.findViewById<View>(android.R.id.content)
        // Above the bottom bar of the screen shown: the tray's, or the browser's once the tray has closed.
        val fragments = activity.supportFragmentManager
        val anchorToBrowserToolbar =
            object : FragmentManager.FragmentLifecycleCallbacks() {
                lateinit var snackbar: Snackbar

                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, state: Bundle?) {
                    v.findViewById<View>(R.id.toolbar)?.let { snackbar.anchorView = it }
                }
            }
        Snackbar.make(content, message, Snackbar.LENGTH_LONG)
            .setAnchorView(view?.findViewById<View>(R.id.tabsToolbar))
            .addCallback(
                object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                    override fun onDismissed(transientBottomBar: Snackbar, event: Int) =
                        fragments.unregisterFragmentLifecycleCallbacks(anchorToBrowserToolbar)
                }
            )
            .setAction(R.string.tabs_tray_undo) {
                // The start tab opened because the last tab went is not wanted once the tabs are back. Removed after
                // the restore, which the undo posts to the main thread, so there is never a moment without a tab.
                val added = store.state.tabs.filter { it.id !in before && StartPage.isStartTab(it) }.map { it.id }
                tabsUseCases.undo.invoke()
                if (added.isNotEmpty()) content.post { tabsUseCases.removeTabs(added) }
            }
            .withoutSystemBarMargins()
            .also { anchorToBrowserToolbar.snackbar = it }
            .show()
        fragments.registerFragmentLifecycleCallbacks(anchorToBrowserToolbar, false)
    }

    private fun updateTabsToolbar(isPrivate: Boolean) {
        val tabsToolbar = requireView().findViewById<TabsToolbar>(R.id.tabsToolbar)
        tabsToolbar.updateToolbar(isPrivate)
    }

    private fun createAndSetupTabsTray(context: Context): TabsTray {
        val layoutManager = LinearLayoutManager(context)
        val privatePageUrl = UrlDisplay.privatePageUrl(context)
        val store = context.components.core.store
        val thumbnailLoader = ThumbnailLoader(context.components.core.thumbnailStorage)
        val startPageCard = ContextCompat.getDrawable(context, R.drawable.archwave_start_thumbnail)
        // A start-page tab shows the mark instead of the blank document, or of a site it showed before Back.
        val cardLoader =
            object : ImageLoader {
                override fun loadIntoView(
                    view: ImageView,
                    request: ImageLoadRequest,
                    placeholder: Drawable?,
                    error: Drawable?,
                ) {
                    val startPage = store.state.findTab(request.id)?.let(StartPage::isStartTab) == true
                    if (startPage) {
                        // Through the loader with an id that has no thumbnail, so it cancels a load still running
                        // for this recycled view and then shows the mark.
                        thumbnailLoader.loadIntoView(
                            view,
                            request.copy(id = START_PAGE_CARD),
                            startPageCard,
                            startPageCard,
                        )
                    } else {
                        thumbnailLoader.loadIntoView(view, request, placeholder, error)
                    }
                }
            }
        val trayStyling =
            TabsTrayStyling(
                itemBackgroundColor = Color.TRANSPARENT,
                selectedItemBackgroundColor = ContextCompat.getColor(context, R.color.aw_surface_3),
                itemTextColor = ContextCompat.getColor(context, R.color.aw_text_1),
                selectedItemTextColor = ContextCompat.getColor(context, R.color.aw_text_1),
            )
        val viewHolderProvider: ViewHolderProvider = { viewGroup ->
            val view = LayoutInflater.from(context).inflate(R.layout.browser_tabstray_item, viewGroup, false)

            val holder = DefaultTabViewHolder(view, cardLoader)
            // The library shows the private page's whole data: URL as the URL line, and a start-page tab as its
            // document's address.
            object : TabViewHolder(view) {
                override var tab by holder::tab

                override fun bind(
                    tab: TabSessionState,
                    isSelected: Boolean,
                    styling: TabsTrayStyling,
                    delegate: TabsTray.Delegate,
                ) {
                    holder.bind(tab, isSelected, styling, delegate)
                    if (tab.content.url == privatePageUrl || StartPage.isStartTab(tab)) {
                        view.findViewById<TextView>(R.id.mozac_browser_tabstray_url).text = ""
                    }
                    if (StartPage.isStartTab(tab)) {
                        view.findViewById<TextView>(R.id.mozac_browser_tabstray_title).text =
                            context.getString(R.string.start_page_tab_title)
                    }
                }

                override fun updateSelectedTabIndicator(showAsSelected: Boolean) =
                    holder.updateSelectedTabIndicator(showAsSelected)
            }
        }
        val tabsAdapter =
            TabsAdapter(
                thumbnailLoader = thumbnailLoader,
                viewHolderProvider = viewHolderProvider,
                styling = trayStyling,
                delegate =
                    object : TabsTray.Delegate {
                        override fun onTabSelected(
                            tab: TabSessionState,
                            source: String?,
                        ) {
                            requireComponents.useCases.tabsUseCases.selectTab(tab.id)
                            closeTabsTray()
                        }

                        override fun onTabClosed(
                            tab: TabSessionState,
                            source: String?,
                        ) {
                            closeTabs(R.string.tabs_tray_tab_closed) {
                                requireComponents.useCases.tabsUseCases.removeTab(tab.id)
                            }
                        }
                    },
            )

        val tabsTray = requireView().findViewById<RecyclerView>(R.id.tabsTray)
        tabsTray.layoutManager = layoutManager
        tabsTray.adapter = tabsAdapter

        TabsTouchHelper {
                closeTabs(R.string.tabs_tray_tab_closed) { requireComponents.useCases.tabsUseCases.removeTab(it.id) }
            }
            .attachToRecyclerView(tabsTray)

        var scrolledToSelected = false
        return object : TabsTray by tabsAdapter {
            override fun updateTabs(tabs: List<TabSessionState>, selectedTabId: String?) {
                tabsAdapter.updateTabs(tabs, selectedTabId)
                showingTabs = tabs.isNotEmpty()
                // Once, to the tab the user came from. The adapter applies its first list at once.
                val selected = tabs.indexOfFirst { it.id == selectedTabId }
                if (!scrolledToSelected && selected >= 0) {
                    tabsTray.scrollToPosition(selected)
                    scrolledToSelected = true
                }
            }
        }
    }

    private companion object {
        // No tab has this id, so the thumbnail loader never finds a picture for it.
        const val START_PAGE_CARD = "archwave-start-page"
    }
}
