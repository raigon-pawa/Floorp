// SPDX-License-Identifier: MPL-2.0
// @colocated-env browser

import {
  allowConvertedAncestor,
  type CompatPolicy,
  requireDebuggableURI,
  requirePermission,
} from "../NRChromeCompatParent.sys.mts";
import {
  asyncWrapper,
  toExceptionDetails,
  toRemoteObject,
} from "../NRChromeCompatTargetChild.sys.mts";
import {
  assert,
  assertEquals,
  runTests,
} from "../../../chrome/test/utils/test_harness.ts";

function policy(permissions: string[], hostAccess = true): CompatPolicy {
  return {
    canAccessURI: () => hostAccess,
    extension: { rawManifest: { permissions } },
  };
}

function throws(fn: () => void, pattern: RegExp, message: string): void {
  try {
    fn();
  } catch (error) {
    assert(pattern.test(String(error)), `${message}: got ${error}`);
    return;
  }
  throw new Error(`${message}: did not throw`);
}

/** Compiles code the way the target actor does, in a throwaway sandbox. */
function sandboxEval(): (source: string) => unknown {
  const sandbox = Cu.Sandbox(
    Services.scriptSecurityManager.createNullPrincipal({}),
    {},
  );
  return (source) => Cu.evalInSandbox(source, sandbox);
}

export async function runAllTests(): Promise<void> {
  await runTests("NRChromeCompat.test.mts", [
    {
      name: "emulated APIs need the permission in the original manifest",
      fn() {
        requirePermission(policy(["debugger"]), "debugger");
        throws(
          () => requirePermission(policy(["tabs"]), "debugger"),
          /"debugger" permission is required/,
          "an ID ending in @chromium-extension alone is not enough",
        );
        throws(
          () => requirePermission(null, "sidePanel"),
          /permission is required/,
          "unknown extension",
        );
      },
    },
    {
      name: "only web pages with host access can be debugged",
      fn() {
        const web = Services.io.newURI("https://example.com/");
        requireDebuggableURI(policy(["debugger"]), web);
        throws(
          () => requireDebuggableURI(policy(["debugger"], false), web),
          /Cannot access a https/,
          "no host permission",
        );
        throws(
          () =>
            requireDebuggableURI(
              policy(["debugger"]),
              Services.io.newURI("about:preferences"),
            ),
          /Cannot access a about/,
          "privileged pages stay off limits",
        );
      },
    },
    {
      name: "frame-ancestors allowing the Chrome ID also allow the converted copy",
      fn() {
        const chrome = "chrome-extension://abc";
        const own = "moz-extension://1234";
        assertEquals(
          allowConvertedAncestor(
            `script-src 'self'; frame-ancestors 'self' ${chrome}; img-src *`,
            chrome,
            own,
          ),
          `script-src 'self'; frame-ancestors 'self' ${chrome} ${own}; img-src *`,
          "own origin appended to the matching directive",
        );
        const other = "frame-ancestors 'self' chrome-extension://other";
        assertEquals(
          allowConvertedAncestor(other, chrome, own),
          other,
          "sites that never allowed this extension stay closed",
        );
      },
    },
    {
      name: "top-level await returns the last expression",
      async fn() {
        const run = sandboxEval();
        const source = asyncWrapper(
          "{const a = await Promise.resolve(1);\na + 1\n}",
          run,
        );
        const value = await (run(source) as () => Promise<unknown>)();
        assertEquals(value, 2, "completion value of the wrapped block");
      },
    },
    {
      name: "a bare return reports Chrome's message so clients retry",
      fn() {
        let error: unknown;
        try {
          sandboxEval()("{return 5\n}");
        } catch (e) {
          error = e;
        }
        const details = toExceptionDetails(error);
        assertEquals(
          details.exception.description,
          "SyntaxError: Illegal return statement",
          "description clients match on",
        );
        assert("scriptId" in details, "syntax errors carry a scriptId");
      },
    },
    {
      name: "results come back by value",
      fn() {
        const result = toRemoteObject(JSON.stringify, { a: [1, 2] });
        assertEquals(JSON.stringify(result.value), '{"a":[1,2]}', "value");
        assertEquals(result.className, "Object", "className");
        assertEquals(
          toRemoteObject(JSON.stringify, undefined).type,
          "undefined",
          "undefined",
        );
      },
    },
  ]);
}
