/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

pluginManagement {
    repositories {
        // buildSrc resolves its plugins separately from the root project, and
        // the Kotlin DSL plugin is only published to the plugin portal.
        gradlePluginPortal()
        mavenCentral()
    }
}
