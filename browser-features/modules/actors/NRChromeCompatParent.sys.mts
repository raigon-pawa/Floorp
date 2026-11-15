/* -*- indent-tabs-mode: nil; js-indent-level: 2 -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/**
 * NRChromeCompatParent - browser side of the Chrome API emulation for
 * converted Chrome extensions (IDs ending in "@chromium-extension").
 *
 * Holds what Chrome keeps in the browser rather than in the extension, so it
 * survives the extension's background page being suspended:
 * - chrome.sidePanel options, mapped onto the extension's sidebar
 * - chrome.debugger sessions, whose privileged commands are forwarded to
 *   NRChromeCompatTargetChild in the debugged tab
 */

import { EXTENSION_ID_SUFFIX } from "../modules/chrome-web-store/Constants.sys.mts";

interface PanelOptions {
  path?: string;
  enabled?: boolean;
}

interface CallArgs {
  tabId?: number;
  windowId?: number;
  path?: string;
  enabled?: boolean;
  method?: string;
  params?: unknown;
}

interface NativeTab {
  linkedBrowser: XULBrowserElement & { documentGlobal: BrowserWindow };
}

interface BrowserWindow extends Window {
  SidebarController: {
    currentID: string | null;
    show(id: string): Promise<boolean>;
    hide(): void;
  };
}

interface ExtensionTrackers {
  tabTracker: {
    getTab(tabId: number): NativeTab;
    getBrowserData(browser: Element): { tabId: number };
  };
  windowTracker: {
    getWindow(windowId: number, context: null): BrowserWindow;
    topWindow: BrowserWindow;
  };
}

interface ExtensionState {
  actors: Set<NRChromeCompatParent>;
  /** Side panel options by tab ID, plus "default" for the extension-wide panel. */
  panels: Record<string, PanelOptions>;
}

const { ExtensionParent } = ChromeUtils.importESModule(
  "resource://gre/modules/ExtensionParent.sys.mjs",
);
const { ExtensionCommon } = ChromeUtils.importESModule(
  "resource://gre/modules/ExtensionCommon.sys.mjs",
);

/** Commands answered by the debugged tab's content process. */
const TARGET_COMMANDS = new Set([
  "Input.dispatchMouseEvent",
  "Input.dispatchKeyEvent",
  "Input.insertText",
  "Runtime.evaluate",
  "Runtime.enable",
  "Runtime.disable",
]);

/** The parts of WebExtensionPolicy the access checks use. */
export interface CompatPolicy {
  canAccessURI(uri: nsIURI): boolean;
  extension?: { rawManifest?: { permissions?: unknown } } | null;
}

/**
 * Converted extensions keep Chrome-only permissions in their original
 * manifest (Gecko drops them from the parsed one). The emulated APIs are only
 * for extensions that asked for them, not for anything that picks an ID
 * ending in "@chromium-extension".
 */
export function requirePermission(
  policy: CompatPolicy | null,
  permission: string,
): void {
  const permissions = policy?.extension?.rawManifest?.permissions;
  if (!Array.isArray(permissions) || !permissions.includes(permission)) {
    throw new Error(`The "${permission}" permission is required`);
  }
}

/** Only web pages the extension has host access to can be debugged. */
export function requireDebuggableURI(
  policy: CompatPolicy | null,
  uri: nsIURI,
): void {
  const web = uri.schemeIs("http") || uri.schemeIs("https");
  if (!web || !policy?.canAccessURI(uri)) {
    throw new Error(`Cannot access a ${uri.scheme}:// URL`);
  }
}

/**
 * API namespace -> Chrome permission it needs. Only the debugger is gated:
 * the side panel just opens the extension's own sidebar, which any extension
 * may do, and the converter drops "sidePanel" from the manifest it writes.
 */
const NAMESPACE_PERMISSIONS: Record<string, string> = {
  debugger: "debugger",
};

/**
 * Sites that let the Chrome extension frame them (frame-ancestors
 * chrome-extension://<id>) get the same allowance for the converted copy,
 * whose origin is moz-extension://<uuid>. Done here because Gecko only lets
 * MV3 extensions make a CSP stricter, never looser.
 */
export function allowConvertedAncestor(
  csp: string,
  chromeOrigin: string,
  ownOrigin: string,
): string {
  return csp.replace(/frame-ancestors[^;]*/gi, (directive) =>
    directive.includes(chromeOrigin) && !directive.includes(ownOrigin)
      ? `${directive} ${ownOrigin}`
      : directive);
}

function onExamineResponse(subject: nsISupports): void {
  let channel: nsIHttpChannel | undefined;
  try {
    channel = subject.QueryInterface?.(Ci.nsIHttpChannel) as nsIHttpChannel;
  } catch {
    return; // not an HTTP channel
  }
  if (!channel) return;
  const loadInfo = channel.loadInfo;
  if (
    loadInfo?.externalContentPolicyType !==
      Ci.nsIContentPolicy.TYPE_SUBDOCUMENT
  ) {
    return;
  }
  const addonId = loadInfo.frameBrowsingContext?.parent?.currentWindowGlobal
    ?.documentPrincipal?.addonId;
  if (!addonId?.endsWith(EXTENSION_ID_SUFFIX)) return;
  const policy = WebExtensionPolicy.getByID(addonId);
  if (!policy) return;
  let csp: string;
  try {
    csp = channel.getResponseHeader("Content-Security-Policy");
  } catch {
    return; // no CSP header
  }
  const chromeOrigin = `chrome-extension://${
    addonId.slice(0, -EXTENSION_ID_SUFFIX.length)
  }`;
  const updated = allowConvertedAncestor(
    csp,
    chromeOrigin,
    `moz-extension://${policy.mozExtensionHostname}`,
  );
  if (updated !== csp) {
    channel.setResponseHeader("Content-Security-Policy", updated, false);
  }
}
// This module loads when a converted extension's first page starts.
Services.obs.addObserver(onExamineResponse, "http-on-examine-response");

const states = new Map<string, ExtensionState>();
/** Tab ID -> add-on IDs debugging it. */
const debuggees = new Map<number, Set<string>>();

function trackers(): ExtensionTrackers {
  return ExtensionParent.apiManager.global as ExtensionTrackers;
}

function stateFor(addonId: string): ExtensionState {
  let state = states.get(addonId);
  if (!state) {
    state = { actors: new Set(), panels: {} };
    states.set(addonId, state);
  }
  return state;
}

function broadcast(addonId: string, event: object): void {
  for (const actor of states.get(addonId)?.actors ?? []) {
    try {
      actor.sendAsyncMessage("NRChromeCompat:Event", event);
    } catch {
      // The page is going away; didDestroy removes it.
    }
  }
}

/** Deliver a CDP event from a debugged tab to the extensions attached to it. */
export function emitDebuggerEvent(
  browser: Element,
  method: string,
  params: unknown,
): void {
  const { tabId } = trackers().tabTracker.getBrowserData(browser);
  for (const addonId of debuggees.get(tabId) ?? []) {
    broadcast(addonId, { type: "debugger.event", tabId, method, params });
  }
}

export class NRChromeCompatParent extends JSWindowActorParent {
  private get addonId(): string {
    const addonId = this.manager?.documentPrincipal?.addonId;
    if (!addonId?.endsWith(EXTENSION_ID_SUFFIX)) {
      throw new Error("Not a converted Chrome extension");
    }
    return addonId;
  }

  receiveMessage(
    message: { name: string; data: { method: string; args: CallArgs } },
  ): unknown {
    if (message.name !== "NRChromeCompat:Call") return null;
    const addonId = this.addonId;
    const state = stateFor(addonId);
    state.actors.add(this);
    const { method, args } = message.data;
    const permission = NAMESPACE_PERMISSIONS[method.split(".")[0]];
    if (permission) {
      requirePermission(
        WebExtensionPolicy.getByID(addonId) as CompatPolicy | null,
        permission,
      );
    }

    switch (method) {
      case "hello":
        return null;
      case "sidePanel.getOptions":
        return state.panels[String(args.tabId)] ?? state.panels.default ?? {};
      case "sidePanel.setOptions": {
        const key = args.tabId === undefined ? "default" : String(args.tabId);
        const options = { ...state.panels[key] };
        if (args.path !== undefined) options.path = args.path;
        if (args.enabled !== undefined) options.enabled = args.enabled;
        state.panels[key] = options;
        return null;
      }
      case "sidePanel.open":
        return this.windowFor(args).SidebarController.show(
          this.sidebarId(addonId),
        ).then(() => null);
      case "sidePanel.close": {
        const { SidebarController } = this.windowFor(args);
        if (SidebarController.currentID === this.sidebarId(addonId)) {
          SidebarController.hide();
        }
        return null;
      }
      case "debugger.attach":
        this.debuggee(addonId, args.tabId);
        if (debuggees.get(args.tabId!)?.has(addonId)) {
          throw new Error(
            `Another debugger is already attached to the tab with id: ${args.tabId}.`,
          );
        }
        debuggees.set(
          args.tabId!,
          (debuggees.get(args.tabId!) ?? new Set()).add(addonId),
        );
        return null;
      case "debugger.detach":
        if (!debuggees.get(args.tabId!)?.delete(addonId)) {
          throw new Error(
            `Debugger is not attached to the tab with id: ${args.tabId}.`,
          );
        }
        return null;
      case "debugger.attached":
        return [...debuggees].filter(([, ids]) => ids.has(addonId)).map((
          [tabId],
        ) => tabId);
      case "debugger.send": {
        if (!debuggees.get(args.tabId!)?.has(addonId)) {
          throw new Error(
            `Debugger is not attached to the tab with id: ${args.tabId}.`,
          );
        }
        if (!TARGET_COMMANDS.has(String(args.method))) {
          throw new Error(`'${args.method}' wasn't found`);
        }
        const windowGlobal = this.debuggee(addonId, args.tabId).linkedBrowser
          .browsingContext?.currentWindowGlobal;
        if (!windowGlobal) throw new Error("Tab has no loaded document");
        return windowGlobal.getActor("NRChromeCompatTarget").sendQuery(
          String(args.method),
          args.params ?? {},
        );
      }
    }
    throw new Error(`Unknown compat method: ${method}`);
  }

  didDestroy(): void {
    for (const state of states.values()) state.actors.delete(this);
  }

  private sidebarId(addonId: string): string {
    return `${ExtensionCommon.makeWidgetId(addonId)}-sidebar-action`;
  }

  private windowFor(args: CallArgs): BrowserWindow {
    const { tabTracker, windowTracker } = trackers();
    if (args.tabId !== undefined) {
      return tabTracker.getTab(args.tabId).linkedBrowser.documentGlobal;
    }
    if (args.windowId !== undefined) {
      return windowTracker.getWindow(args.windowId, null);
    }
    return windowTracker.topWindow;
  }

  /** The tab to debug; only pages the extension has host access to. */
  private debuggee(addonId: string, tabId: number | undefined): NativeTab {
    if (typeof tabId !== "number") throw new Error("A tabId target is required");
    let tab: NativeTab;
    try {
      tab = trackers().tabTracker.getTab(tabId);
    } catch {
      throw new Error(`No tab with given id ${tabId}.`);
    }
    requireDebuggableURI(
      WebExtensionPolicy.getByID(addonId) as CompatPolicy | null,
      tab.linkedBrowser.currentURI,
    );
    return tab;
  }
}
