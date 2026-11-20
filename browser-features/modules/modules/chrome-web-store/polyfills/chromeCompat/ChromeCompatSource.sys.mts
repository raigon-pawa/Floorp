/* -*- indent-tabs-mode: nil; js-indent-level: 2 -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/**
 * Chrome API polyfills bundled into converted Chrome extensions:
 * chrome.sidePanel, chrome.debugger and externally_connectable, built on the
 * NRChromeCompat actors (see actors/NRChromeCompat*.sys.mts). The sources
 * must stay free of backticks and "${" because they are String.raw literals.
 */

/** Directory inside the converted extension that holds the polyfills. */
export const POLYFILL_DIR = "__floorp_polyfills__";

export const CHROME_COMPAT_CORE = String.raw`(function () {
  "use strict";
  // Shared helpers for Floorp's Chrome API polyfills. Loaded first in every
  // page of a converted Chrome extension.
  if (globalThis.__floorpCompat) return;
  var api = globalThis.chrome;
  var bridge = globalThis.__floorpChromeCompat;

  // Calls into the browser (NRChromeCompatParent).
  function call(method, args) {
    return new Promise(function (resolve, reject) {
      if (typeof bridge !== "function") {
        reject(new Error("Chrome compatibility bridge is unavailable in this browser"));
        return;
      }
      bridge(method, JSON.stringify(args || {}), function (error, json) {
        if (error) reject(new Error(error));
        else resolve(JSON.parse(json));
      });
    });
  }

  // chrome.runtime.lastError for polyfilled callback-style calls.
  var lastError = null;
  // Gecko's lastError starts as a lazy getter that replaces itself on first
  // read; read it once so the getter kept below is the final one and calling
  // it can't overwrite this override.
  void api.runtime.lastError;
  var nativeLastError = Object.getOwnPropertyDescriptor(api.runtime, "lastError");
  Object.defineProperty(api.runtime, "lastError", {
    configurable: true,
    enumerable: true,
    get: function () {
      if (lastError) return lastError;
      return nativeLastError && nativeLastError.get ? nativeLastError.get.call(api.runtime) : undefined;
    },
  });

  // Wraps a promise-returning function so it also accepts a trailing callback.
  function asyncApi(fn) {
    return function () {
      var args = Array.prototype.slice.call(arguments);
      var callback = typeof args[args.length - 1] === "function" ? args.pop() : null;
      var promise;
      try {
        promise = Promise.resolve(fn.apply(null, args));
      } catch (error) {
        promise = Promise.reject(error);
      }
      if (!callback) return promise;
      promise.then(
        function (value) { callback(value); },
        function (error) {
          lastError = { message: String((error && error.message) || error) };
          try { callback(); } finally { lastError = null; }
        }
      );
      return undefined;
    };
  }

  function makeEvent() {
    var listeners = new Set();
    return {
      addListener: function (listener) { listeners.add(listener); },
      removeListener: function (listener) { listeners.delete(listener); },
      hasListener: function (listener) { return listeners.has(listener); },
      hasListeners: function () { return listeners.size > 0; },
      listeners: listeners,
      emit: function () {
        var args = arguments;
        Array.from(listeners).forEach(function (listener) {
          try { listener.apply(null, args); } catch (error) { console.error(error); }
        });
      },
    };
  }

  // Events pushed by the browser, as { type, ... }.
  var browserEvents = makeEvent();
  globalThis.addEventListener("__floorpChromeCompatEvent", function (event) {
    browserEvents.emit(JSON.parse(event.detail));
  });
  // Subscribes this page to those events.
  call("hello").catch(function () {});

  // Enum objects Chrome exposes and Firefox does not.
  function defineEnum(namespace, name, values) {
    if (!namespace || namespace[name]) return;
    var result = {};
    Object.keys(values).forEach(function (key) { result[key] = values[key]; });
    namespace[name] = result;
  }
  var dnr = api.declarativeNetRequest;
  defineEnum(dnr, "RuleActionType", { BLOCK: "block", REDIRECT: "redirect", ALLOW: "allow", UPGRADE_SCHEME: "upgradeScheme", MODIFY_HEADERS: "modifyHeaders", ALLOW_ALL_REQUESTS: "allowAllRequests" });
  defineEnum(dnr, "HeaderOperation", { APPEND: "append", SET: "set", REMOVE: "remove" });
  defineEnum(dnr, "DomainType", { FIRST_PARTY: "firstParty", THIRD_PARTY: "thirdParty" });
  defineEnum(dnr, "RequestMethod", { CONNECT: "connect", DELETE: "delete", GET: "get", HEAD: "head", OPTIONS: "options", PATCH: "patch", POST: "post", PUT: "put", OTHER: "other" });

  // Gecko refuses chrome://newtab ("Illegal URL"); without a url, tabs and
  // windows open on the browser's own new tab page / home page instead.
  function dropNewTabUrl(namespace, name) {
    var native = namespace && namespace[name];
    if (typeof native !== "function") return;
    namespace[name] = function (properties) {
      var args = Array.prototype.slice.call(arguments);
      if (properties && /^chrome:\/\/newtab\/?$/.test(properties.url)) {
        args[0] = Object.assign({}, properties);
        delete args[0].url;
      }
      return native.apply(namespace, args);
    };
  }
  dropNewTabUrl(api.tabs, "create");
  dropNewTabUrl(api.windows, "create");

  // The background runs as an event page here, not a service worker. Chrome
  // extensions gate their background-only setup (alarms, keepalives, bridge
  // pairing) on this global, so mark the background page with it.
  if (location.pathname.indexOf("/_generated_background_page.html") === 0 && !("ServiceWorkerGlobalScope" in globalThis)) {
    globalThis.ServiceWorkerGlobalScope = function ServiceWorkerGlobalScope() {};
  }

  // window.close() in a side panel closes the panel, as it does in Chrome.
  var nativeClose = window.close.bind(window);
  window.close = function () {
    var views = [];
    try { views = browser.extension.getViews({ type: "sidebar" }); } catch (error) { /* not available */ }
    if (views.indexOf(window) === -1) return nativeClose();
    browser.windows.getCurrent().then(function (current) {
      return call("sidePanel.close", { windowId: current.id });
    }).catch(function () {});
  };

  globalThis.__floorpCompat = { call: call, asyncApi: asyncApi, makeEvent: makeEvent, browserEvents: browserEvents };
})();
`;

export const SIDE_PANEL_POLYFILL = String.raw`(function () {
  "use strict";
  // chrome.sidePanel on top of the extension's Firefox sidebar. A panel path
  // becomes the sidebar page (sidebarAction.setPanel), so the panel is a
  // top-level document as in Chrome (extensions refuse to run framed).
  // Firefox has one sidebar per window and reloads it whenever the active
  // tab's panel URL differs, which extensions read as the panel closing. So a
  // tab's panel is set for its whole window and stays loaded across tab
  // switches, until its tab closes.
  // shortcut: one live panel per window; Chrome keeps one per tab.
  var core = globalThis.__floorpCompat;
  if (!core || chrome.sidePanel || !browser.sidebarAction) return;

  var panelTabs = new Map(); // window ID -> tab ID whose panel it shows
  function setPanel(path, tabId) {
    var panel = browser.runtime.getURL(path);
    if (typeof tabId !== "number") return browser.sidebarAction.setPanel({ panel: panel });
    return browser.tabs.get(tabId).then(function (tab) {
      panelTabs.set(tab.windowId, tabId);
      return browser.sidebarAction.setPanel({ windowId: tab.windowId, panel: panel });
    });
  }
  if (browser.tabs && location.pathname.indexOf("/_generated_background_page.html") === 0) {
    browser.tabs.onRemoved.addListener(function (tabId, info) {
      if (panelTabs.get(info.windowId) !== tabId) return;
      panelTabs.delete(info.windowId);
      if (!info.isWindowClosing) browser.sidebarAction.setPanel({ windowId: info.windowId, panel: null }).catch(function () {});
    });
  }

  function done() { return undefined; }
  function call(name) {
    return core.asyncApi(function (options) {
      return core.call("sidePanel." + name, options).then(function (result) {
        return result === null ? undefined : result;
      });
    });
  }

  var behavior = { openPanelOnActionClick: false };
  chrome.sidePanel = {
    setOptions: core.asyncApi(function (options) {
      options = options || {};
      var applied = Promise.resolve();
      if (typeof options.path === "string") applied = setPanel(options.path, options.tabId);
      // shortcut: enabled:false is remembered (getOptions) but does not hide
      // the panel; Firefox has no per-tab switch for an extension's sidebar.
      return applied.then(function () { return core.call("sidePanel.setOptions", options); }).then(done);
    }),
    getOptions: call("getOptions"),
    open: call("open"),
    close: call("close"),
    // shortcut: kept per page, so set it at startup as Chrome's docs advise.
    setPanelBehavior: core.asyncApi(function (next) {
      Object.assign(behavior, next);
      return Promise.resolve();
    }),
    getPanelBehavior: core.asyncApi(function () {
      return Promise.resolve(Object.assign({}, behavior));
    }),
  };

  var action = chrome.action || chrome.browserAction;
  if (action && location.pathname.indexOf("/_generated_background_page.html") === 0) {
    action.onClicked.addListener(function (tab) {
      if (behavior.openPanelOnActionClick) chrome.sidePanel.open({ windowId: tab.windowId });
    });
  }
})();
`;


/** Sidebar page for tabs without a side panel (Chrome shows none there). */
export const SIDE_PANEL_BLANK_HTML = String.raw`<!doctype html>
<html>
  <head>
    <meta charset="utf-8" />
    <style>html, body { margin: 0; height: 100%; background: transparent; }</style>
  </head>
  <body></body>
</html>
`;

export const DEBUGGER_POLYFILL = String.raw`(function () {
  "use strict";
  // chrome.debugger for tab targets, covering the Chrome DevTools Protocol
  // subset that browser-automation extensions use. Screenshots, navigation
  // and network events come from WebExtension APIs; trusted input, script
  // evaluation and console events come from the browser through the bridge.
  var core = globalThis.__floorpCompat;
  if (!core || chrome.debugger) return;

  var onEvent = core.makeEvent();
  var onDetach = core.makeEvent();
  var attached = new Set(); // tab IDs attached from this page
  var domains = new Map(); // tab ID -> Set of enabled CDP domains
  var loaders = new Map(); // tab ID -> navigation counter, used as loaderId
  var hooked = false;

  function tabIdOf(target) {
    if (!target || typeof target.tabId !== "number") {
      throw new Error("Only tab targets ({ tabId }) are supported");
    }
    return target.tabId;
  }

  function enabled(tabId, domain) {
    var set = domains.get(tabId);
    return !!set && set.has(domain);
  }

  function forget(tabId) {
    attached.delete(tabId);
    domains.delete(tabId);
    loaders.delete(tabId);
  }

  // Page and Network events, synthesised from WebExtension events.
  function hookEvents() {
    if (hooked) return;
    hooked = true;
    browser.webNavigation.onCommitted.addListener(function (details) {
      if (!attached.has(details.tabId)) return;
      if (details.frameId === 0) {
        loaders.set(details.tabId, (loaders.get(details.tabId) || 0) + 1);
        // The console listener lives in the document that just went away.
        if (enabled(details.tabId, "Runtime")) {
          core.call("debugger.send", { tabId: details.tabId, method: "Runtime.enable" }).catch(function () {});
        }
      }
      if (!enabled(details.tabId, "Page")) return;
      var frame = { id: String(details.frameId), url: details.url, loaderId: String(loaders.get(details.tabId) || 0) };
      if (details.frameId !== 0) frame.parentId = String(details.parentFrameId);
      onEvent.emit({ tabId: details.tabId }, "Page.frameNavigated", { frame: frame });
    });
    browser.tabs.onRemoved.addListener(function (tabId) {
      if (!attached.has(tabId)) return;
      forget(tabId);
      core.call("debugger.detach", { tabId: tabId }).catch(function () {});
      onDetach.emit({ tabId: tabId }, "target_closed");
    });
    if (!browser.webRequest) return;
    var filter = { urls: ["<all_urls>"] };
    function network(method, build) {
      return function (details) {
        if (!enabled(details.tabId, "Network")) return;
        onEvent.emit({ tabId: details.tabId }, method, build(details));
      };
    }
    browser.webRequest.onBeforeRequest.addListener(network("Network.requestWillBeSent", function (d) {
      return { requestId: d.requestId, documentURL: d.documentUrl, type: d.type, timestamp: d.timeStamp / 1000, request: { url: d.url, method: d.method } };
    }), filter);
    browser.webRequest.onCompleted.addListener(network("Network.responseReceived", function (d) {
      return { requestId: d.requestId, type: d.type, timestamp: d.timeStamp / 1000, response: { url: d.url, status: d.statusCode } };
    }), filter);
    browser.webRequest.onErrorOccurred.addListener(network("Network.loadingFailed", function (d) {
      return { requestId: d.requestId, type: d.type, timestamp: d.timeStamp / 1000, errorText: d.error };
    }), filter);
  }

  function captureScreenshot(tabId, params) {
    var options = { format: params.format === "jpeg" ? "jpeg" : "png" };
    if (options.format === "jpeg" && typeof params.quality === "number") options.quality = params.quality;
    var clip = params.clip;
    var ready = Promise.resolve();
    if (clip) {
      // CDP clips are in page coordinates; captureTab takes viewport ones.
      ready = browser.scripting.executeScript({
        target: { tabId: tabId },
        func: function () { return [window.scrollX, window.scrollY, window.devicePixelRatio]; },
      }).then(function (results) {
        var view = results[0].result;
        options.rect = { x: clip.x - view[0], y: clip.y - view[1], width: clip.width, height: clip.height };
        options.scale = (clip.scale || 1) * view[2];
      });
    }
    return ready.then(function () {
      return browser.tabs.captureTab(tabId, options);
    }).then(function (dataUrl) {
      return { data: dataUrl.slice(dataUrl.indexOf(",") + 1) };
    });
  }

  function setDomain(tabId, domain, on) {
    var set = domains.get(tabId) || new Set();
    if (on) set.add(domain); else set.delete(domain);
    domains.set(tabId, set);
  }

  function sendCommand(target, method, params) {
    var tabId = tabIdOf(target);
    params = params || {};
    if (!attached.has(tabId)) {
      return Promise.reject(new Error("Debugger is not attached to the tab with id: " + tabId + "."));
    }
    var parts = method.split(".");
    if (parts[1] === "enable" || parts[1] === "disable") {
      setDomain(tabId, parts[0], parts[1] === "enable");
      if (parts[0] !== "Runtime") return Promise.resolve({});
    }
    switch (method) {
      case "Page.captureScreenshot":
        return captureScreenshot(tabId, params);
      case "Page.getFrameTree":
        return browser.tabs.get(tabId).then(function (tab) {
          return { frameTree: { frame: { id: "0", loaderId: String(loaders.get(tabId) || 0), url: tab.url || "" } } };
        });
      // shortcut: accepted but not emulated. Background tabs stay unfocused
      // and page dialogs are left to the user; implement in the browser
      // (NRChromeCompatParent) if an extension depends on them.
      case "Emulation.setFocusEmulationEnabled":
      case "Input.cancelDragging":
      case "Page.handleJavaScriptDialog":
      case "Page.bringToFront":
        return Promise.resolve({});
    }
    return core.call("debugger.send", { tabId: tabId, method: method, params: params });
  }

  chrome.debugger = {
    onEvent: onEvent,
    onDetach: onDetach,
    attach: core.asyncApi(function (target) {
      var tabId = tabIdOf(target);
      return core.call("debugger.attach", { tabId: tabId }).then(function () {
        attached.add(tabId);
        hookEvents();
      });
    }),
    detach: core.asyncApi(function (target) {
      var tabId = tabIdOf(target);
      forget(tabId);
      return core.call("debugger.detach", { tabId: tabId }).then(function () {});
    }),
    sendCommand: core.asyncApi(sendCommand),
    getTargets: core.asyncApi(function () {
      return Promise.all([browser.tabs.query({}), core.call("debugger.attached")]).then(function (results) {
        return results[0].map(function (tab) {
          return { id: String(tab.id), tabId: tab.id, type: "page", title: tab.title || "", url: tab.url || "", attached: results[1].indexOf(tab.id) !== -1, faviconUrl: tab.favIconUrl };
        });
      });
    }),
  };

  // Console and exception events from the debugged page.
  core.browserEvents.addListener(function (event) {
    if (event.type === "debugger.event" && attached.has(event.tabId)) {
      onEvent.emit({ tabId: event.tabId }, event.method, event.params);
    }
  });
  // Sessions outlive this page (the background page gets suspended).
  core.call("debugger.attached").then(function (tabIds) {
    tabIds.forEach(function (tabId) { attached.add(tabId); });
    if (tabIds.length) hookEvents();
  }).catch(function () {});
})();
`;

export const EXTERNALLY_CONNECTABLE_POLYFILL = String.raw`(function () {
  "use strict";
  // externally_connectable for converted Chrome extensions: web pages the
  // manifest lists call chrome.runtime.sendMessage(chromeId, ...) through
  // ExternallyConnectableContent.js, which relays over a port to here, where
  // the extension's chrome.runtime.onMessageExternal listeners answer.
  if (globalThis.__floorpExternallyConnectable) return;
  globalThis.__floorpExternallyConnectable = true;

  var PORT_NAME = "__floorp_external";
  var listeners = new Set();
  var ownOrigin = new URL(chrome.runtime.getURL("")).origin;

  chrome.runtime.onMessageExternal = {
    addListener: function (listener) { listeners.add(listener); },
    removeListener: function (listener) { listeners.delete(listener); },
    hasListener: function (listener) { return listeners.has(listener); },
    hasListeners: function () { return listeners.size > 0; },
  };

  // Chrome semantics: the first sendResponse wins; a listener keeps the
  // channel open by returning true (or a promise).
  function dispatch(message, sender) {
    return new Promise(function (resolve) {
      var done = false;
      var open = false;
      function respond(value) {
        if (done) return;
        done = true;
        resolve({ response: value === undefined ? null : value });
      }
      Array.from(listeners).forEach(function (listener) {
        var result;
        try {
          result = listener(message, sender, respond);
        } catch (error) {
          console.error(error);
          return;
        }
        if (result === true) open = true;
        else if (result && typeof result.then === "function") {
          open = true;
          result.then(respond, function (error) { respond(undefined); console.error(error); });
        }
      });
      if (!open && !done) {
        done = true;
        resolve({ error: "The message port closed before a response was received." });
      }
    });
  }

  chrome.runtime.onConnect.addListener(function (port) {
    if (port.name !== PORT_NAME) return;
    var from = port.sender || {};
    var origin;
    try { origin = new URL(from.url).origin; } catch (error) { return port.disconnect(); }
    var sender = { url: from.url, origin: origin, frameId: from.frameId };
    if (from.tab) sender.tab = from.tab;
    port.onMessage.addListener(function (request) {
      dispatch(request.message, sender).then(function (reply) {
        reply.id = request.id;
        try { port.postMessage(reply); } catch (error) { /* page went away */ }
      });
    });
  });


  // Sites tell their extension parent apart by location.ancestorOrigins,
  // expecting chrome-extension://<id>. Gecko gives moz-extension://<uuid> and
  // the property can't be redefined, so in scripts loaded by frames this
  // extension embeds, point it at Location.prototype.__floorpAncestorOrigins,
  // which ExternallyConnectableContent.js defines with the Chrome origin.
  if (browser.webRequest && browser.webRequest.filterResponseData) {
    var NEEDLE = ".location.ancestorOrigins";
    browser.webRequest.onBeforeRequest.addListener(function (details) {
      var parent = (details.frameAncestors || [])[0];
      if (!parent || parent.url.indexOf(ownOrigin + "/") !== 0) return;
      var filter = browser.webRequest.filterResponseData(details.requestId);
      var chunks = [];
      filter.ondata = function (event) { chunks.push(event.data); };
      filter.onstop = function () {
        var decoder = new TextDecoder();
        var text = chunks.map(function (chunk) { return decoder.decode(chunk, { stream: true }); }).join("") + decoder.decode();
        if (text.indexOf(NEEDLE) === -1) {
          chunks.forEach(function (chunk) { filter.write(chunk); });
        } else {
          filter.write(new TextEncoder().encode(text.split(NEEDLE).join(".location.__floorpAncestorOrigins")));
        }
        filter.close();
      };
    }, { urls: ["<all_urls>"], types: ["script"] }, ["blocking"]);
  }
})();
`;

export const EXTERNALLY_CONNECTABLE_CONTENT = String.raw`(function () {
  "use strict";
  // Page half of the externally_connectable polyfill. Runs in the extension's
  // isolated world on the sites its manifest lists and gives the page
  // chrome.runtime.sendMessage(chromeId, ...). Uses Firefox's Xray APIs
  // (wrappedJSObject, exportFunction, cloneInto) so it is in place before
  // any page script runs.
  var page = window.wrappedJSObject;
  var chromeId = browser.runtime.id.replace(/@chromium-extension$/, "");
  var chromeOrigin = "chrome-extension://" + chromeId;
  var ownOrigin = new URL(browser.runtime.getURL("")).origin;

  var port = null;
  var pending = new Map();
  var nextId = 0;
  function connect() {
    if (port) return port;
    port = browser.runtime.connect({ name: "__floorp_external" });
    port.onMessage.addListener(function (reply) {
      var resolve = pending.get(reply.id);
      pending.delete(reply.id);
      if (resolve) resolve(reply);
    });
    port.onDisconnect.addListener(function () {
      port = null;
      pending.forEach(function (resolve) {
        resolve({ error: "Could not establish connection. Receiving end does not exist." });
      });
      pending.clear();
    });
    return port;
  }
  function send(message) {
    return new Promise(function (resolve) {
      var id = ++nextId;
      pending.set(id, resolve);
      try {
        connect().postMessage({ id: id, message: message });
      } catch (error) {
        pending.delete(id);
        resolve({ error: String(error && error.message || error) });
      }
    });
  }

  // --- chrome.runtime.sendMessage / lastError on the page ---
  if (!page.chrome) page.chrome = new page.Object();
  if (!page.chrome.runtime) page.chrome.runtime = new page.Object();
  var runtime = page.chrome.runtime;
  var previous = runtime.sendMessage; // another converted extension's shim

  if (!Object.getOwnPropertyDescriptor(runtime, "lastError")) {
    Object.defineProperty(runtime, "lastError", {
      configurable: true,
      get: exportFunction(function () { return page.__floorpLastError; }, window),
    });
  }
  runtime.sendMessage = exportFunction(function (extensionId, message, options, callback) {
    if (extensionId !== chromeId && typeof previous === "function") {
      return previous.apply(runtime, arguments);
    }
    if (typeof options === "function") { callback = options; options = undefined; }
    // Like Chrome, an extension that is not installed is reported through
    // lastError / a rejected promise, never thrown: sites probe several IDs.
    var request = extensionId === chromeId
      ? send(JSON.parse(page.JSON.stringify(message === undefined ? null : message)))
      : Promise.resolve({ error: "Could not establish connection. Receiving end does not exist." });
    if (typeof callback === "function") {
      request.then(function (reply) {
        page.__floorpLastError = reply.error ? cloneInto({ message: reply.error }, window) : undefined;
        try {
          callback(reply.error ? undefined : cloneInto(reply.response, window));
        } finally {
          page.__floorpLastError = undefined;
        }
      });
      return undefined;
    }
    return new page.Promise(exportFunction(function (resolve, reject) {
      request.then(function (reply) {
        if (reply.error) reject(new page.Error(reply.error));
        else resolve(cloneInto(reply.response, window));
      });
    }, window));
  }, window);


  // --- frames embedded by this extension ---
  // Sites check the embedding origin against chrome-extension://<id>. That
  // origin is moz-extension://<uuid> here, so translate it both ways for
  // messages between this frame and its extension parent.
  var embedded = false;
  try {
    embedded = window.parent !== window && location.ancestorOrigins[0] === ownOrigin;
  } catch (error) { /* no ancestorOrigins */ }
  if (!embedded) return;

  // Read by scripts the background relay rewrote (see the comment there).
  Object.defineProperty(page.Location.prototype, "__floorpAncestorOrigins", {
    configurable: true,
    get: exportFunction(function () {
      var real = location.ancestorOrigins;
      var list = [];
      for (var i = 0; i < real.length; i++) list.push(real[i] === ownOrigin ? chromeOrigin : real[i]);
      return cloneInto(list, window);
    }, window),
  });

  var realParent = page.parent;
  var fakeParent = new page.Object();
  function toReal(origin) { return origin === chromeOrigin ? ownOrigin : origin; }
  fakeParent.postMessage = exportFunction(function (data, targetOrigin, transfer) {
    if (targetOrigin && typeof targetOrigin === "object") {
      var options = new page.Object();
      options.targetOrigin = toReal(targetOrigin.targetOrigin);
      options.transfer = targetOrigin.transfer;
      return realParent.postMessage(data, options);
    }
    return transfer === undefined
      ? realParent.postMessage(data, toReal(targetOrigin))
      : realParent.postMessage(data, toReal(targetOrigin), transfer);
  }, window);
  Object.defineProperty(page, "parent", {
    configurable: true,
    get: exportFunction(function () { return fakeParent; }, window),
  });

  function translate(event) {
    var raw = event.wrappedJSObject || event;
    if (raw.origin !== ownOrigin || raw.source !== realParent) return raw;
    var fake = new page.Object();
    fake.type = raw.type;
    fake.data = raw.data;
    fake.origin = chromeOrigin;
    fake.source = fakeParent;
    fake.ports = raw.ports;
    fake.lastEventId = raw.lastEventId;
    fake.isTrusted = true;
    fake.target = page;
    fake.currentTarget = page;
    fake.timeStamp = raw.timeStamp;
    ["preventDefault", "stopPropagation", "stopImmediatePropagation"].forEach(function (name) {
      fake[name] = exportFunction(function () { raw[name](); }, window);
    });
    return fake;
  }

  var addListener = page.EventTarget.prototype.addEventListener;
  var removeListener = page.EventTarget.prototype.removeEventListener;
  var wrappers = new WeakMap();
  function wrapperFor(listener) {
    var wrapper = wrappers.get(listener);
    if (!wrapper) {
      wrapper = exportFunction(function (event) {
        var translated = translate(event);
        return typeof listener === "function"
          ? listener.call(page, translated)
          : listener.handleEvent(translated);
      }, window);
      wrappers.set(listener, wrapper);
    }
    return wrapper;
  }
  page.addEventListener = exportFunction(function (type, listener, options) {
    var target = listener && type === "message" ? wrapperFor(listener) : listener;
    return addListener.call(page, type, target, options);
  }, window);
  page.removeEventListener = exportFunction(function (type, listener, options) {
    var target = listener && type === "message" && wrappers.has(listener) ? wrappers.get(listener) : listener;
    return removeListener.call(page, type, target, options);
  }, window);
})();
`;

/** Polyfill files by path inside the converted extension. */
export const CHROME_COMPAT_FILES: Record<string, string> = {
  [`${POLYFILL_DIR}/ChromeCompatCore.js`]: CHROME_COMPAT_CORE,
  [`${POLYFILL_DIR}/SidePanelPolyfill.js`]: SIDE_PANEL_POLYFILL,
  [`${POLYFILL_DIR}/sidepanel.html`]: SIDE_PANEL_BLANK_HTML,
  [`${POLYFILL_DIR}/DebuggerPolyfill.js`]: DEBUGGER_POLYFILL,
  [`${POLYFILL_DIR}/ExternallyConnectablePolyfill.js`]: EXTERNALLY_CONNECTABLE_POLYFILL,
  [`${POLYFILL_DIR}/ExternallyConnectableContent.js`]: EXTERNALLY_CONNECTABLE_CONTENT,
};
