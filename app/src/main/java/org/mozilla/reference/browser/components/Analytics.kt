/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.components

import android.content.Context
import mozilla.components.concept.base.crash.Breadcrumb
import mozilla.components.lib.crash.Crash
import mozilla.components.lib.crash.CrashReporter
import mozilla.components.lib.crash.service.CrashReporterService

/** Component group for crash handling. There is no telemetry and no crash report ever leaves the device. */
class Analytics(private val context: Context) {
    /** Records crashes in the on-device database that about:crashes lists. */
    val crashReporter: CrashReporter by lazy {
        CrashReporter(
            context = context,
            services = listOf(LocalOnlyCrashService),
            // No prompt or notification offering to send a report: there is nobody to send it to.
            useLegacyReporting = false,
        )
    }
}

/** [CrashReporter] refuses to run without a service. This one uploads nothing. */
private object LocalOnlyCrashService : CrashReporterService {
    override val id = "local"
    override val name = "Local"

    override fun createCrashReportUrl(identifier: String): String? = null

    override fun report(crash: Crash.UncaughtExceptionCrash): String? = null

    override fun report(crash: Crash.NativeCodeCrash): String? = null

    override fun report(throwable: Throwable, breadcrumbs: ArrayList<Breadcrumb>): String? = null
}
