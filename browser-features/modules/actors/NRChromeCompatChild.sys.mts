/* -*- indent-tabs-mode: nil; js-indent-level: 2 -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/**
 * NRChromeCompatChild - bridge for pages of converted Chrome extensions.
 *
 * Exposes `__floorpChromeCompat(method, argsJson, callback)` to every
 * extension page whose add-on ID ends in "@chromium-extension", and relays
 * parent events as "__floorpChromeCompatEvent" CustomEvents. The polyfills in
 * modules/chrome-web-store/polyfills build chrome.sidePanel and
 * chrome.debugger on top of it. JSON strings cross the boundary so nothing
 * has to be cloned between compartments.
 */

import { EXTENSION_ID_SUFFIX } from "../modules/chrome-web-store/Constants.sys.mts";

type BridgeCallback = (error: string | null, resultJson: string) => void;

export class NRChromeCompatChild extends JSWindowActorChild {
  handleEvent(event: Event): void {
    if (event.type !== "DOMDocElementInserted") return;
    const win = this.contentWindow;
    const addonId = this.document?.nodePrincipal?.addonId;
    if (!win || !addonId?.endsWith(EXTENSION_ID_SUFFIX)) return;

    Cu.exportFunction(
      (method: string, argsJson: string, callback: BridgeCallback) => {
        this.sendQuery("NRChromeCompat:Call", {
          method: String(method),
          args: JSON.parse(String(argsJson)),
        }).then(
          (result: unknown) => callback(null, JSON.stringify(result ?? null)),
          (error: unknown) =>
            callback(String((error as Error)?.message ?? error), "null"),
        );
      },
      win,
      { defineAs: "__floorpChromeCompat" },
    );
  }

  receiveMessage(message: { name: string; data?: unknown }): void {
    const win = this.contentWindow as (Window & typeof globalThis) | null;
    if (message.name !== "NRChromeCompat:Event" || !win) return;
    win.dispatchEvent(
      new win.CustomEvent("__floorpChromeCompatEvent", {
        detail: JSON.stringify(message.data),
      }),
    );
  }
}
