/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/** SDK levels, build tools and the version name for the app's Gradle build. */
object Config {
    const val buildToolsVersion = "37.0.0"
    const val compileSdkMajorVersion = 37
    const val compileSdkMinorVersion = 2
    const val minSdkVersion = 26
    const val targetSdkVersion = 37
    const val jvmTargetCompatibility = 17

    /** Archwave's own version. Debug builds append "-debug"; the version code is generated from the build time. */
    const val versionName = "0.1.0"
}
