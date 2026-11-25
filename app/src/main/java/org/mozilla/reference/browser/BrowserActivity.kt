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
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
import com.google.android.material.snackbar.Snackbar.LENGTH_LONG
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.WebExtensionState
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.intent.ext.EXTRA_SESSION_ID
import mozilla.components.support.base.feature.ActivityResultHandler
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.android.view.setupPersistentInsets
import mozilla.components.support.utils.SafeIntent
import mozilla.components.support.webextensions.WebExtensionPopupObserver
import org.mozilla.reference.browser.addons.WebExtensionActionPopupActivity
import org.mozilla.reference.browser.browser.BrowserFragment
import org.mozilla.reference.browser.browser.CrashIntegration
import org.mozilla.reference.browser.ext.components

/** Activity that holds the [BrowserFragment]. */
open class BrowserActivity : AppCompatActivity() {
    private var crashSnackbar: Snackbar? = null

    private val sessionId: String?
        get() = SafeIntent(intent).getStringExtra(EXTRA_SESSION_ID)

    private val webExtensionPopupObserver by lazy {
        WebExtensionPopupObserver(components.core.store, ::openPopup)
    }

    /** Returns a new instance of [BrowserFragment] to display. */
    open fun createBrowserFragment(sessionId: String?): Fragment = BrowserFragment.create(sessionId)

    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_main)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        window.setupPersistentInsets()

        components.notificationsDelegate.bindToActivity(this)

        if (savedInstanceState == null) {
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
                        }
                    )
                }
            },
            false,
        )
        showNoticeIfCrashedSinceLastStart()
        lifecycle.addObserver(webExtensionPopupObserver)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    supportFragmentManager.fragments.forEach {
                        if (it is UserInteractionHandler && it.onBackPressed()) {
                            return
                        }
                    }
                }
            },
        )
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
                components.core.engine.createView(context, attrs).asView()
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
                .show()
        }
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
