/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
import com.google.android.material.snackbar.Snackbar.LENGTH_LONG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.ExtensionsProcessAction
import mozilla.components.browser.state.action.WebExtensionAction
import mozilla.components.browser.state.selector.findCustomTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.WebExtensionState
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.addons.R as addonsR
import mozilla.components.feature.intent.ext.EXTRA_SESSION_ID
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.feature.ActivityResultHandler
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.utils.SafeIntent
import mozilla.components.support.webextensions.ExtensionsProcessDisabledPromptObserver
import mozilla.components.support.webextensions.WebExtensionOptionsPageObserver
import mozilla.components.support.webextensions.WebExtensionPopupObserver
import org.mozilla.geckoview.GeckoView
import org.mozilla.reference.browser.addons.WebExtensionActionPopupActivity
import org.mozilla.reference.browser.addons.openInBrowserTab
import org.mozilla.reference.browser.browser.BrowserFragment
import org.mozilla.reference.browser.browser.CrashIntegration
import org.mozilla.reference.browser.browser.withoutSystemBarMargins
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.ext.enableEdgeToEdgeFromAndroid13
import org.mozilla.reference.browser.settings.ClearDataDialogFragment

/** Activity that holds the [BrowserFragment]. */
open class BrowserActivity : AppCompatActivity() {
    private var crashSnackbar: Snackbar? = null

    private val sessionId: String?
        get() = SafeIntent(intent).getStringExtra(EXTRA_SESSION_ID)

    private val webExtensionPopupObserver by lazy {
        WebExtensionPopupObserver(components.core.store, ::openPopup)
    }

    /**
     * Whether this screen shows the notices about the app itself (it crashed last time, a clear of browsing data did
     * not finish). Not over a custom tab or web app, which runs inside another app's flow and would use them up.
     */
    protected open val showsAppNotices: Boolean = true

    /** Returns a new instance of [BrowserFragment] to display. */
    open fun createBrowserFragment(sessionId: String?): Fragment = BrowserFragment.create(sessionId)

    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_main)
        super.onCreate(savedInstanceState)
        // Below Android 13 the toolbar's ImeInsetsSynchronizer does nothing either; the classic window that those
        // versions keep is resized for the keyboard by adjustResize.
        enableEdgeToEdgeFromAndroid13()

        components.notificationsDelegate.bindToActivity(this)
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            NotificationManager.askBeforeOfferingSettings(this, components.notificationsDelegate),
            true,
        )

        if (savedInstanceState == null) {
            // Not when the system replays the intent of a task reopened from recents.
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
                NotificationManager.onWebNotificationTapped(components, intent)
            }
            supportFragmentManager.beginTransaction().apply {
                replace(R.id.container, createBrowserFragment(sessionId))
                commit()
            }
        }

        // The offer to restore a crashed tab belongs to the browser screen: it sits above that screen's toolbar,
        // goes away with it (the tabs tray replaces it) and comes back when the screen does.
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, state: Bundle?) {
                    val toolbar = v.findViewById<View>(R.id.toolbar) ?: return
                    f.viewLifecycleOwner.lifecycle.addObserver(
                        CrashIntegration(components.core.store, sessionId) { crashed ->
                            onTabCrashedChanged(toolbar, crashed)
                            // A crashed tab has nothing to draw: its last frame kept the old size after a rotation
                            // and covered half the screen. Show the plain background until it is restored.
                            v.findViewById<View>(R.id.engineView)?.isInvisible = crashed
                        }
                    )
                }
            },
            false,
        )
        if (showsAppNotices) {
            showNoticeIfCrashedSinceLastStart()
            // A clear of browsing data that the process did not live to finish is offered again at once, not hidden.
            ClearDataDialogFragment.showIfInterrupted(this, supportFragmentManager)
        }
        lifecycle.addObserver(webExtensionPopupObserver)
        addExtensionObservers()
        secureWhilePrivate()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    supportFragmentManager.fragments.forEach {
                        if (it is UserInteractionHandler && it.onBackPressed()) {
                            return
                        }
                    }
                    // Nothing left to go back to: leave the app like Home does, keeping the tabs. A custom tab that
                    // lives in its caller's task just closes, so the caller is not sent away with it.
                    if (isTaskRoot) moveTaskToBack(true) else finish()
                }
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Everything else that arrives here was already handled by the intent processors before it was sent.
        NotificationManager.onWebNotificationTapped(components, intent)
    }

    @Suppress("DEPRECATION") // ComponentActivity wants us to use registerForActivityResult
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Logger.info(
            "Activity onActivityResult received with " +
                "requestCode: $requestCode, resultCode: $resultCode, data: $data"
        )

        supportFragmentManager.fragments.forEach {
            if (it is ActivityResultHandler && it.onActivityResult(requestCode, data, resultCode)) {
                return
            }
        }

        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        super.onDestroy()
        components.notificationsDelegate.unBindActivity(this)
    }

    override fun onUserLeaveHint() {
        supportFragmentManager.fragments.forEach {
            if (it is UserInteractionHandler && it.onHomePressed()) {
                return
            }
        }

        super.onUserLeaveHint()
    }

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet,
    ): View? =
        when (name) {
            EngineView::class.java.name -> {
                components.core.engine.createView(context, attrs).asView().also {
                    // Until a page paints, GeckoView shows a dark grey (#2A2A2E) cover; the browser is black.
                    // shortcut: GeckoEngineView (Android Components 160) holds its GeckoView as child 0 and has no
                    // API for the cover colour; the warning tells when an upgrade moves it.
                    val geckoView = (it as? ViewGroup)?.getChildAt(0) as? GeckoView
                    geckoView?.coverUntilFirstPaint(Color.BLACK)
                        ?: Logger.warn("No GeckoView at child 0 of the engine view: its pre-paint cover stays grey")
                }
            }

            else -> {
                super.onCreateView(parent, name, context, attrs)
            }
        }

    /** A crashed tab stays blank until it is restored, so the offer stays up until it is taken or moot. */
    private fun onTabCrashedChanged(toolbar: View, crashed: Boolean) {
        crashSnackbar?.dismiss()
        crashSnackbar =
            if (crashed) {
                Snackbar.make(toolbar, R.string.crash_report_non_fatal_message, LENGTH_INDEFINITE)
                    .setAction(R.string.crash_report_non_fatal_action) {
                        components.useCases.sessionUseCases.crashRecovery.invoke()
                    }
                    // Up for as long as the tab is crashed, so it must not sit on top of the address bar.
                    .setAnchorView(toolbar.takeIf { it.isVisible })
                    .withoutSystemBarMargins()
                    // A swipe would leave a blank tab with no explanation and no way to bring the offer back.
                    .setBehavior(
                        object : BaseTransientBottomBar.Behavior() {
                            override fun canSwipeDismissView(child: View) = false
                        }
                    )
                    .also { it.show() }
            } else {
                null
            }
    }

    /** A crash of the main process closes the app without a word, so say what happened when it is opened again. */
    private fun showNoticeIfCrashedSinceLastStart() = lifecycleScope.launch {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this@BrowserActivity)
        val lastCheck = prefs.getLong(PREF_LAST_CRASH_CHECK, 0)
        prefs.edit { putLong(PREF_LAST_CRASH_CHECK, System.currentTimeMillis()) }

        // Nothing is ever sent, so "unsent" means every crash recorded since then.
        if (components.analytics.crashReporter.unsentCrashReportsSince(lastCheck).any { it.isFatalCrash }) {
            val toolbar = findViewById<View>(R.id.toolbar)
            Snackbar.make(
                    toolbar ?: findViewById(android.R.id.content),
                    R.string.archwave_crashed_last_time,
                    LENGTH_LONG,
                )
                .setAnchorView(toolbar?.takeIf { it.isVisible })
                .withoutSystemBarMargins()
                .show()
        }
    }

    /**
     * While a private tab (or private custom tab) is shown, the window may not be captured: no screenshots or
     * recordings, and no Recents card picture, which the system keeps on disk after the private session ends.
     */
    private fun secureWhilePrivate() {
        components.core.store.flowScoped(this, Dispatchers.Main) { flow ->
            flow
                .map { state -> (sessionId?.let(state::findCustomTab) ?: state.selectedTab)?.content?.private == true }
                .distinctUntilChanged()
                .collect { private ->
                    if (private) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
        }
    }

    /**
     * Options pages requested by extensions open in a tab. That observer runs for the whole life of the activity, not
     * only while it is visible, because the request usually comes from a popup or add-on screen on top of it.
     */
    private fun addExtensionObservers() {
        val store = components.core.store
        val optionsPageObserver =
            WebExtensionOptionsPageObserver(
                store,
                onOpenOptionsPage = { page ->
                    // The store accepts no new request while one is pending, so clear it first. Every live instance
                    // (custom tabs and PWAs too) observes it: only the one that clears the request opens the tab.
                    val pending = store.state.extensions.values.filter { it.activeOptionsPage == page }
                    pending.forEach { store.dispatch(WebExtensionAction.ClearOptionsPageSession(it.id)) }
                    if (pending.isNotEmpty()) openInBrowserTab(page.url)
                },
            )
        optionsPageObserver.start()
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) = optionsPageObserver.stop()
            }
        )

        // After repeated crashes the engine stops starting the extensions process and asks whether to try again.
        var processDialog: AlertDialog? = null
        lifecycle.addObserver(
            ExtensionsProcessDisabledPromptObserver(store) {
                if (processDialog?.isShowing == true) return@ExtensionsProcessDisabledPromptObserver
                processDialog =
                    AlertDialog.Builder(this)
                        .setTitle(addonsR.string.mozac_feature_extensions_manager_notification_title_text)
                        .setMessage(addonsR.string.mozac_feature_extensions_manager_notification_content_text)
                        .setPositiveButton(
                            addonsR.string.mozac_feature_extensions_manager_notification_restart_button
                        ) { _, _ ->
                            store.dispatch(ExtensionsProcessAction.EnabledAction)
                        }
                        // The add-ons manager then offers the restart in its header.
                        .setNegativeButton(R.string.archwave_addons_keep_extensions_disabled) { _, _ ->
                            store.dispatch(ExtensionsProcessAction.DisabledAction)
                        }
                        .setOnDismissListener { store.dispatch(ExtensionsProcessAction.ShowPromptAction(show = false)) }
                        .setCancelable(false)
                        .show()
            }
        )
    }

    private fun openPopup(webExtensionState: WebExtensionState) {
        val intent = Intent(this, WebExtensionActionPopupActivity::class.java)
        intent.putExtra("web_extension_id", webExtensionState.id)
        intent.putExtra("web_extension_name", webExtensionState.name)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    private companion object {
        const val PREF_LAST_CRASH_CHECK = "archwave.last_crash_check"
    }
}
