/* -*- indent-tabs-mode: nil; js-indent-level: 2 -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/** One monochrome line icon and the chrome elements that should use it. */
export type ArchwaveIcon = {
  /** CSS selectors whose `list-style-image` is replaced. */
  selectors: readonly string[];
  /** SVG path data drawn on a 24x24 grid. */
  paths: readonly string[];
  /** Fill the shape instead of stroking it (e.g. the starred bookmark). */
  filled?: boolean;
};
