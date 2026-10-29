// SPDX-License-Identifier: MPL-2.0
// @colocated-env browser

import { ARCHWAVE_ICONS, buildIconCSS, iconToDataURI } from "../icons.ts";
import { assert, runTests } from "../../../test/utils/test_harness.ts";

function testIconsAreValidSVG(): void {
  const parser = new DOMParser();
  for (const icon of ARCHWAVE_ICONS) {
    const uri = iconToDataURI(icon);
    const prefix = "data:image/svg+xml,";
    assert(uri.startsWith(prefix), `${icon.selectors[0]} is an SVG data URI`);
    const svg = decodeURIComponent(uri.slice(prefix.length));
    const doc = parser.parseFromString(svg, "image/svg+xml");
    assert(
      doc.getElementsByTagName("parsererror").length === 0,
      `${icon.selectors[0]} parses as SVG`,
    );
    assert(
      doc.querySelectorAll("path").length === icon.paths.length,
      `${icon.selectors[0]} keeps every path`,
    );
    assert(
      svg.includes("context-fill"),
      `${icon.selectors[0]} follows the toolbar icon color`,
    );
  }
}

function testIconRulesParse(): void {
  const sheet = new CSSStyleSheet();
  sheet.replaceSync(buildIconCSS());
  const selectors = ARCHWAVE_ICONS.reduce(
    (count, icon) => count + icon.selectors.length,
    0,
  );
  assert(
    sheet.cssRules.length === ARCHWAVE_ICONS.length,
    "every icon produces one CSS rule",
  );
  const listed = Array.from(sheet.cssRules).reduce(
    (count, rule) =>
      count + (rule as CSSStyleRule).selectorText.split(",").length,
    0,
  );
  assert(listed === selectors, "no icon selector is dropped by the parser");
}

export async function runAllTests(): Promise<void> {
  await runTests("archwave-theme.test.ts", [
    {
      name: "archwave icons are valid context-fill SVGs",
      fn: testIconsAreValidSVG,
    },
    { name: "archwave icon rules parse", fn: testIconRulesParse },
  ]);
}

await runAllTests();
