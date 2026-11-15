/* -*- indent-tabs-mode: nil; js-indent-level: 2 -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/**
 * NRChromeCompatTargetChild - the page side of the chrome.debugger emulation.
 *
 * Runs the Chrome DevTools Protocol commands that need the content process:
 * trusted mouse/keyboard input, script evaluation and console capture. It only
 * acts on queries from NRChromeCompatParent and never on anything the page
 * sends.
 */

import type { PrivilegedMouseWindow } from "./webscraper/types.ts";

interface MouseParams {
  type: string;
  x: number;
  y: number;
  button?: string;
  buttons?: number;
  clickCount?: number;
  modifiers?: number;
  deltaX?: number;
  deltaY?: number;
}

interface KeyParams {
  type: string;
  key?: string;
  code?: string;
  text?: string;
  windowsVirtualKeyCode?: number;
  location?: number;
  autoRepeat?: boolean;
  modifiers?: number;
}

interface EvaluateParams {
  expression: string;
  awaitPromise?: boolean;
  timeout?: number;
}

interface RemoteObject {
  type: string;
  subtype?: string;
  className?: string;
  value?: unknown;
  description?: string;
}

interface ConsoleApiMessage {
  innerID: number;
  level: string;
  arguments: unknown[];
  filename?: string;
  lineNumber?: number;
  columnNumber?: number;
  timeStamp?: number;
}

type TargetWindow = PrivilegedMouseWindow & typeof globalThis;

const MOUSE_EVENT_TYPES: Record<string, string> = {
  mousePressed: "mousedown",
  mouseReleased: "mouseup",
  mouseMoved: "mousemove",
};
const MOUSE_BUTTONS: Record<string, number> = {
  left: 0,
  middle: 1,
  right: 2,
  back: 3,
  forward: 4,
};
// CDP modifier bit -> [nsIDOMWindowUtils MODIFIER_* bit, key, code]
const MODIFIERS: [number, number, string, string][] = [
  [1, 0x1, "Alt", "AltLeft"],
  [2, 0x2, "Control", "ControlLeft"],
  [4, 0x8, "Meta", "MetaLeft"],
  [8, 0x4, "Shift", "ShiftLeft"],
];
const CONSOLE_LEVELS: Record<string, string> = { warn: "warning" };

function geckoModifiers(cdpModifiers = 0): number {
  return MODIFIERS.reduce(
    (bits, [cdp, gecko]) => (cdpModifiers & cdp ? bits | gecko : bits),
    0,
  );
}

export class NRChromeCompatTargetChild extends JSWindowActorChild {
  private stopConsole: (() => void) | null = null;
  private tip: nsITextInputProcessor | null = null;

  receiveMessage(message: { name: string; data?: unknown }): unknown {
    const win = this.contentWindow as TargetWindow | null;
    if (!win) throw new Error("No window for this target");
    switch (message.name) {
      case "Input.dispatchMouseEvent":
        return this.mouse(win, message.data as MouseParams);
      case "Input.dispatchKeyEvent":
        return this.key(win, message.data as KeyParams);
      case "Input.insertText":
        win.windowUtils.sendContentCommandEvent(
          "insertText",
          undefined,
          String((message.data as { text: string }).text),
        );
        return {};
      case "Runtime.evaluate":
        return this.evaluate(win, message.data as EvaluateParams);
      case "Runtime.enable":
        this.startConsole(win);
        return {};
      case "Runtime.disable":
        this.didDestroy();
        return {};
    }
    throw new Error(`'${message.name}' wasn't found`);
  }

  didDestroy(): void {
    this.stopConsole?.();
    this.stopConsole = null;
  }

  private mouse(win: TargetWindow, p: MouseParams): object {
    const modifiers = geckoModifiers(p.modifiers);
    if (p.type === "mouseWheel") {
      const dx = p.deltaX ?? 0;
      const dy = p.deltaY ?? 0;
      win.windowUtils.sendWheelEvent(
        p.x,
        p.y,
        dx,
        dy,
        0,
        win.WheelEvent.DOM_DELTA_PIXEL,
        modifiers,
        Math.trunc(dx),
        Math.trunc(dy),
        0,
      );
      return {};
    }
    const type = MOUSE_EVENT_TYPES[p.type];
    if (!type) throw new Error(`Unsupported mouse event type: ${p.type}`);
    win.synthesizeMouseEvent?.(
      type,
      p.x,
      p.y,
      {
        button: MOUSE_BUTTONS[p.button ?? "none"] ?? 0,
        buttons: p.buttons ?? 0,
        clickCount: p.clickCount ?? 0,
        modifiers,
      },
      // Synchronous dispatch in this process works for background tabs too.
      // shortcut: events over out-of-process iframes are not forwarded;
      // switch to parent-side async dispatch if cross-origin frames matter.
      { toWindow: true, isAsyncEnabled: false },
    );
    return {};
  }

  private key(win: TargetWindow, p: KeyParams): object {
    if (p.type === "char") {
      if (p.text) {
        win.windowUtils.sendContentCommandEvent("insertText", undefined, p.text);
      }
      return {};
    }
    // One processor per page, so modifier keys the client pressed stay down
    // between calls, as they do in Chrome.
    this.tip ??= Cc["@mozilla.org/text-input-processor;1"].createInstance(
      Ci.nsITextInputProcessor,
    );
    const tip = this.tip;
    if (!tip.beginInputTransactionForTests(win as unknown as mozIDOMWindow)) {
      throw new Error("Keyboard input is unavailable for this tab");
    }
    const KeyboardEvent = win.KeyboardEvent;
    // TextInputProcessor only honours modifiers whose keys it saw go down, so
    // press any the event names that are not already held, around its
    // keyDown only.
    const isModifier = MODIFIERS.some(([, , key]) => key === p.key);
    const held = p.type === "keyUp" || isModifier
      ? []
      : MODIFIERS.filter(([cdp, , key]) =>
        (p.modifiers ?? 0) & cdp && !tip.getModifierState(key)
      ).map(([, , key, code]) => new KeyboardEvent("", { key, code }));
    for (const modifier of held) tip.keydown(modifier);
    try {
      // A key with text types it; rawKeyDown and named keys must not.
      const key = p.type === "keyDown" && p.text && p.text.length === 1 &&
          p.text >= " "
        ? p.text
        : (p.key ?? "");
      const event = new KeyboardEvent("", {
        key,
        code: p.code ?? "",
        keyCode: p.windowsVirtualKeyCode ?? 0,
        location: p.location ?? 0,
        repeat: p.autoRepeat ?? false,
      });
      const flags = key.length > 1
        ? tip.KEY_NON_PRINTABLE_KEY
        : tip.KEY_FORCE_PRINTABLE_KEY;
      if (p.type === "keyUp") tip.keyup(event, flags);
      else tip.keydown(event, flags);
    } finally {
      for (const modifier of held.reverse()) tip.keyup(modifier);
    }
    return {};
  }

  private async evaluate(win: TargetWindow, p: EvaluateParams) {
    // Same principal as the page, so no Xrays; exempt from the page's CSP.
    const sandbox = Cu.Sandbox(win as unknown as nsIPrincipal, {
      sandboxPrototype: win,
      wantXrays: false,
      sameZoneAs: win,
    });
    const run = (source: string): unknown =>
      Cu.evalInSandbox(source, sandbox, "latest", "debugger eval code", 1);
    try {
      let value: unknown;
      try {
        value = run(p.expression);
      } catch (error) {
        if (!/await is only valid/.test(String(error))) throw error;
        value = (run(asyncWrapper(p.expression, run)) as () => unknown)();
      }
      if (p.awaitPromise && isThenable(value)) {
        value = await withTimeout(win, value, p.timeout);
      }
      // The page's own JSON, so toJSON hooks and getters run in page context.
      const stringify = run("JSON.stringify") as (v: unknown) => string;
      return { result: toRemoteObject(stringify, value) };
    } catch (error) {
      return { exceptionDetails: toExceptionDetails(error) };
    }
  }

  private startConsole(win: TargetWindow): void {
    if (this.stopConsole) return;
    const innerId = this.manager?.innerWindowId;
    const storage = Cc["@mozilla.org/consoleAPI-storage;1"].getService(
      Ci.nsIConsoleAPIStorage,
    );
    const onConsole = (subject: { wrappedJSObject: ConsoleApiMessage }) => {
      const m = subject.wrappedJSObject;
      if (m.innerID !== innerId) return;
      this.sendAsyncMessage("NRChromeCompatTarget:Event", {
        method: "Runtime.consoleAPICalled",
        params: {
          type: CONSOLE_LEVELS[m.level] ?? m.level,
          args: Array.from(m.arguments ?? [], describeConsoleArgument),
          timestamp: m.timeStamp ?? Date.now(),
          stackTrace: {
            callFrames: [{
              url: m.filename ?? "",
              lineNumber: m.lineNumber ?? 0,
              columnNumber: m.columnNumber ?? 0,
            }],
          },
        },
      });
    };
    const onError = {
      observe: (message: nsIConsoleMessage) => {
        if (!(message instanceof Ci.nsIScriptError)) return;
        if (message.innerWindowID !== innerId) return;
        if (message.logLevel !== Ci.nsIConsoleMessage.error) return;
        this.sendAsyncMessage("NRChromeCompatTarget:Event", {
          method: "Runtime.exceptionThrown",
          params: {
            exceptionDetails: {
              text: message.errorMessage,
              url: message.sourceName,
              lineNumber: message.lineNumber,
              columnNumber: message.columnNumber,
              timestamp: message.timeStamp,
              exception: { description: message.errorMessage },
            },
          },
        });
      },
    };
    storage.addLogEventListener(
      onConsole,
      Cc["@mozilla.org/systemprincipal;1"].createInstance(Ci.nsIPrincipal),
    );
    Services.console.registerListener(onError);
    this.stopConsole = () => {
      storage.removeLogEventListener(onConsole);
      Services.console.unregisterListener(onError);
    };
    // Messages logged before the debugger enabled the Runtime domain.
    for (const cached of storage.getEvents(String(innerId))) {
      onConsole({ wrappedJSObject: cached as ConsoleApiMessage });
    }
    win.addEventListener("pagehide", () => this.didDestroy(), { once: true });
  }
}

function isThenable(value: unknown): value is PromiseLike<unknown> {
  return (typeof value === "object" || typeof value === "function") &&
    value !== null && typeof (value as PromiseLike<unknown>).then === "function";
}

function withTimeout(
  win: TargetWindow,
  promise: PromiseLike<unknown>,
  timeout?: number,
): Promise<unknown> {
  if (!timeout) return Promise.resolve(promise);
  return new Promise((resolve, reject) => {
    const timer = win.setTimeout(
      // Chrome's wording; clients match on it to report a timeout.
      () => reject(new Error("Script execution was terminated")),
      timeout,
    );
    promise.then(resolve, reject).then(() => win.clearTimeout(timer));
  });
}

/**
 * Top-level `await` (CDP replMode) is not valid in a classic script, so wrap
 * the code in an async function. The completion value is recovered by
 * returning the trailing expression: the first split that still parses wins.
 */
export function asyncWrapper(
  expression: string,
  compile: (source: string) => unknown,
): string {
  const block = expression.trim().match(/^\{([\s\S]*)\}$/);
  const body = (block ? block[1] : expression).trim().replace(/;+$/, "");
  const candidates = [`return (\n${body}\n)`];
  const splits = [...body.matchAll(/[;\n]/g)].map((m) => m.index).reverse();
  for (const index of splits.slice(0, 12)) {
    const tail = body.slice(index + 1).trim();
    if (tail) {
      candidates.push(`${body.slice(0, index + 1)}\nreturn (\n${tail}\n)`);
    }
  }
  candidates.push(body);
  for (const candidate of candidates) {
    const source = `(async () => {\n${candidate}\n})`;
    try {
      compile(source);
      return source;
    } catch {
      // Not valid with this split; try the next one.
    }
  }
  return `(async () => {\n${body}\n})`;
}

export function toRemoteObject(
  stringify: (v: unknown) => string | undefined,
  value: unknown,
): RemoteObject {
  if (value === undefined) return { type: "undefined" };
  if (value === null) return { type: "object", subtype: "null", value: null };
  const type = typeof value;
  if (type === "function" || type === "symbol" || type === "bigint") {
    return { type, description: String(value) };
  }
  if (type !== "object") return { type, value, description: String(value) };
  let json: string | undefined;
  try {
    json = stringify(value);
  } catch {
    // Circular or throwing getters: fall back to the description.
  }
  const className = Object.prototype.toString.call(value).slice(8, -1);
  return {
    type: "object",
    ...(Array.isArray(value) ? { subtype: "array" } : {}),
    className,
    ...(json === undefined
      ? { description: String(value) }
      : { value: JSON.parse(json) }),
  };
}

export function toExceptionDetails(error: unknown) {
  const e = error as Partial<Error> & { lineNumber?: number };
  const className = String(e?.name ?? "Error");
  // Chrome's wording for a bare `return`; clients retry in a function on it.
  const message = /return not in function/.test(String(e?.message))
    ? "Illegal return statement"
    : String(e?.message ?? error);
  const syntaxError = className === "SyntaxError";
  return {
    text: "Uncaught",
    lineNumber: e?.lineNumber ?? 0,
    columnNumber: 0,
    ...(syntaxError ? { scriptId: "0" } : {
      stackTrace: { callFrames: [] },
    }),
    exception: {
      type: "object",
      subtype: "error",
      className,
      description: `${className}: ${message}`,
    },
  };
}

function describeConsoleArgument(argument: unknown): RemoteObject {
  const type = typeof argument;
  if (argument === null || type === "undefined") {
    return { type, description: String(argument) };
  }
  if (type === "object" || type === "function") {
    let description: string;
    try {
      description = JSON.stringify(argument) ?? String(argument);
    } catch {
      description = String(argument);
    }
    return { type, description };
  }
  return { type, value: argument };
}
