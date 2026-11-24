/* -*- indent-tabs-mode: nil; js-indent-level: 2 -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import { emitDebuggerEvent } from "./NRChromeCompatParent.sys.mts";

/** Routes CDP events from a debugged tab to the extensions attached to it. */
export class NRChromeCompatTargetParent extends JSWindowActorParent {
  receiveMessage(
    message: { name: string; data: { method: string; params: unknown } },
  ): void {
    const browser = this.browsingContext?.top?.embedderElement;
    if (message.name !== "NRChromeCompatTarget:Event" || !browser) return;
    emitDebuggerEvent(browser, message.data.method, message.data.params);
  }
}
