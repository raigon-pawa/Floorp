/* -*- indent-tabs-mode: nil; js-indent-level: 2 -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import { createEffect, createSignal, onCleanup } from "solid-js";
import {
  noraComponent,
  NoraComponentBase,
} from "#features-chrome/utils/base.ts";
import archwaveStyle from "./archwave.css?inline";
import { buildIconCSS } from "./icons.ts";

export const ARCHWAVE_ENABLED_PREF = "floorp.archwave.enabled";
const STYLE_ID = "floorp-archwave-theme";

export const ARCHWAVE_CSS = `${archwaveStyle}\n${buildIconCSS()}`;

/**
 * Monochrome "archwave" chrome theme: black/white/grey palette and line icons.
 * Toggle with the `floorp.archwave.enabled` pref (on by default).
 */
@noraComponent(import.meta.hot)
export default class ArchwaveTheme extends NoraComponentBase {
  init(): void {
    const head = document?.head;
    if (!head) {
      this.logger.warn("document.head is unavailable; skip archwave theme.");
      return;
    }

    const [enabled, setEnabled] = createSignal(
      Services.prefs.getBoolPref(ARCHWAVE_ENABLED_PREF, true),
    );
    const observer = () =>
      setEnabled(Services.prefs.getBoolPref(ARCHWAVE_ENABLED_PREF, true));
    Services.prefs.addObserver(ARCHWAVE_ENABLED_PREF, observer);
    onCleanup(() =>
      Services.prefs.removeObserver(ARCHWAVE_ENABLED_PREF, observer)
    );

    createEffect(() => {
      if (!enabled()) return;
      try {
        const style = document.createElement("style");
        style.id = STYLE_ID;
        style.textContent = ARCHWAVE_CSS;
        head.appendChild(style);
        onCleanup(() => style.remove());
      } catch (error) {
        console.error("[ArchwaveTheme]", "Failed to apply theme:", error);
      }
    });
  }
}
