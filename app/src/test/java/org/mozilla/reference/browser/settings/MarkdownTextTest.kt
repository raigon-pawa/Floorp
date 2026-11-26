/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTextTest {
    @Test
    fun `licence notices read as plain text`() {
        val markdown =
            """
            # Licenses for Third-Party Dependencies

            Binary distributions of this software incorporate code from a number of third-party dependencies.
            These dependencies are available under a variety of free and open source licenses,
            the details of which are reproduced below.

            * [Apache License 2.0](#Apache-2.0)
            * [MIT License](#MIT)
            -------------

            ## Apache License 2.0
            <span id="Apache-2.0"></span>

            The following text applies to code linked from these dependencies:

            * [rkv 0.20.0]( https://github.com/mozilla/rkv )

            ```
                                 Apache License
                           Version 2.0, January 2004
               1. Definitions.

                  "License" shall mean the terms
                  and conditions.
               (a) You must
                   give a copy.

            Copyright (c) 2015 A
            Copyright (c) 2016 B
            Permission is hereby granted,
            free of charge.

            1. Definitions
            -------------
            ```
            """
                .trimIndent()
        val expected =
            """
            Licenses for Third-Party Dependencies

            Binary distributions of this software incorporate code from a number of third-party dependencies. These dependencies are available under a variety of free and open source licenses, the details of which are reproduced below.

            • Apache License 2.0
            • MIT License

            Apache License 2.0

            The following text applies to code linked from these dependencies:

            • rkv 0.20.0 (https://github.com/mozilla/rkv)

                                 Apache License
                           Version 2.0, January 2004
               1. Definitions.

                  "License" shall mean the terms and conditions.
               (a) You must
                   give a copy.

            Copyright (c) 2015 A
            Copyright (c) 2016 B
            Permission is hereby granted, free of charge.

            1. Definitions
            -------------
            """
                .trimIndent()
        assertEquals(expected, MarkdownText.toPlainText(markdown))
    }
}
