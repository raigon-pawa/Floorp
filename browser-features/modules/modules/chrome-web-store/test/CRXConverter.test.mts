// SPDX-License-Identifier: MPL-2.0
// @colocated-env browser

import {
  CRXConverterClass,
  injectCompatScripts,
  rewriteOwnOrigin,
} from "../CRXConverter.sys.mts";
import {
  assert,
  assertEquals,
  runTests,
  type TestCase,
} from "../../../../chrome/test/utils/test_harness.ts";

const tests: TestCase[] = [
  // --- Own-origin rewrite ---
  {
    name: "rewriteOwnOrigin points chrome-extension://<id> at the real origin",
    fn() {
      const own = 'chrome.runtime.getURL("").slice(0, -1)';
      assertEquals(
        rewriteOwnOrigin(
          "e.url.startsWith(`chrome-extension://${chrome.runtime.id}/`)",
        ),
        "e.url.startsWith(`${" + own + "}/`)",
        "template literal (Claude's sender check)",
      );
      assertEquals(
        rewriteOwnOrigin("x='chrome-extension://'+browser.runtime.id+'/'"),
        'x=(browser.runtime.getURL("").slice(0, -1))+\'/\'',
        "concatenation keeps the namespace",
      );
      const untouched = [
        "`chrome-extension://${otherId}/cb.html`",
        '"chrome-extension://"+chrome.runtime.id.length',
        "u.startsWith(chrome.runtime.getURL(''))",
      ];
      for (const source of untouched) {
        assertEquals(rewriteOwnOrigin(source), source, `unchanged: ${source}`);
      }
    },
  },

  // --- Chrome compat page injection ---
  {
    name: "injectCompatScripts adds page polyfills at the top of <head>",
    fn() {
      const inject = injectCompatScripts([
        "__floorp_polyfills__/ChromeCompatCore.js",
        "__floorp_polyfills__/DebuggerPolyfill.js",
        "__floorp_polyfills__/ExternallyConnectablePolyfill.js",
        "__floorp_polyfills__/OffscreenPolyfill.js",
      ]);
      const html = inject(
        '<!doctype html><html><head lang="en"><script src="/app.js"></script></head></html>',
      );
      assertEquals(
        html,
        '<!doctype html><html><head lang="en">' +
          '<script src="/__floorp_polyfills__/ChromeCompatCore.js"></script>' +
          '<script src="/__floorp_polyfills__/DebuggerPolyfill.js"></script>' +
          '<script src="/app.js"></script></head></html>',
        "core and debugger go in, background-only polyfills stay out",
      );
      assertEquals(inject(html), html, "injecting twice changes nothing");
      assert(
        inject("<p>no head</p>").startsWith("<script"),
        "pages without <head> still get the scripts",
      );
    },
  },

  // --- Constructor / options ---
  {
    name: "CRXConverterClass can be instantiated with default options",
    fn() {
      const converter = new CRXConverterClass();
      assert(converter !== null, "should create instance");
    },
  },
  {
    name: "CRXConverterClass accepts custom options",
    fn() {
      const converter = new CRXConverterClass({
        validateCode: false,
        sanitizeDNR: false,
        minGeckoVersion: "120.0",
      });
      assert(converter !== null, "should create instance with custom options");
    },
  },
  {
    name: "CRXConverterClass accepts partial options",
    fn() {
      const converter = new CRXConverterClass({ validateCode: false });
      assert(converter !== null, "should create instance with partial options");
    },
  },

  // --- convert error handling ---
  {
    name: "convert returns failure for empty ArrayBuffer",
    async fn() {
      const converter = new CRXConverterClass();
      const emptyBuffer = new ArrayBuffer(0);
      const result = await converter.convert(emptyBuffer, "test-id", {
        name: "Test Extension",
        version: "1.0",
      });
      assertEquals(result.success, false, "should fail for empty buffer");
      assert(result.error !== undefined, "should have an error");
    },
  },
  {
    name: "convert returns failure for random garbage data",
    async fn() {
      const converter = new CRXConverterClass();
      const garbage = new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8]).buffer;
      const result = await converter.convert(garbage, "test-id", {
        name: "Test",
        version: "1.0",
      });
      assertEquals(result.success, false, "should fail for garbage data");
      assert(result.error !== undefined, "should have an error");
    },
  },
  {
    name: "convert returns failure for truncated CRX header",
    async fn() {
      const converter = new CRXConverterClass();
      // CRX3 magic: Cr24 = 0x43723234
      const header = new Uint8Array([
        0x43, 0x72, 0x32, 0x34, 0x03, 0x00, 0x00, 0x00,
      ]);
      const result = await converter.convert(header.buffer, "test-id", {
        name: "Test",
        version: "1.0",
      });
      assertEquals(result.success, false, "should fail for truncated CRX");
    },
  },

  // --- ConversionResult shape ---
  {
    name: "failed conversion result has expected shape",
    async fn() {
      const converter = new CRXConverterClass();
      const result = await converter.convert(new ArrayBuffer(0), "x", {
        name: "X",
        version: "0",
      });
      assert("success" in result, "should have success property");
      assert("warnings" in result, "should have warnings property");
      assert(Array.isArray(result.warnings), "warnings should be an array");
    },
  },
];

export async function runAllTests(): Promise<void> {
  await runTests("CRXConverter.test.mts", tests);
}
