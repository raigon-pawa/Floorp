/* -*- indent-tabs-mode: nil; js-indent-level: 2 -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import type { ArchwaveIcon } from "./types.ts";

const STAR =
  "M12 3.5l2.6 5.3 5.9.9-4.25 4.1 1 5.85L12 16.9l-5.25 2.75 1-5.85L3.5 9.7l5.9-.9z";

/**
 * Thin line icons that match the wave line-art wallpaper. They are painted with
 * `context-fill`, so they follow the toolbar text color like the stock icons.
 */
export const ARCHWAVE_ICONS: readonly ArchwaveIcon[] = [
  { selectors: ["#back-button"], paths: ["M19 12H5", "M11 6l-6 6 6 6"] },
  { selectors: ["#forward-button"], paths: ["M5 12h14", "M13 6l6 6-6 6"] },
  {
    selectors: ["#reload-button"],
    paths: ["M19.5 12a7.5 7.5 0 1 1-2.2-5.3", "M19.5 4.5v4h-4"],
  },
  {
    selectors: ["#stop-button"],
    paths: ["M6.5 6.5l11 11", "M17.5 6.5l-11 11"],
  },
  {
    selectors: ["#home-button"],
    paths: ["M4 11l8-7 8 7", "M6.5 9v11h11V9", "M10.5 20v-5h3v5"],
  },
  {
    selectors: ["#downloads-button"],
    paths: ["M12 4v11", "M7 10l5 5 5-5", "M5 20h14"],
  },
  {
    selectors: ["#PanelUI-menu-button"],
    paths: ["M4 7h16", "M4 12h16", "M4 17h16"],
  },
  {
    selectors: ["#unified-extensions-button"],
    paths: ["M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z", "M14 14h6v6h-6z"],
  },
  {
    selectors: [
      "#tabs-newtab-button",
      "#new-tab-button",
      "#vertical-tabs-newtab-button",
    ],
    paths: ["M12 5v14", "M5 12h14"],
  },
  {
    selectors: [".tab-close-button"],
    paths: ["M7.5 7.5l9 9", "M16.5 7.5l-9 9"],
  },
  { selectors: ["#alltabs-button"], paths: ["M6 9.5l6 6 6-6"] },
  { selectors: ["#sidebar-button"], paths: ["M4 5h16v14H4z", "M9.5 5v14"] },
  { selectors: ["#star-button"], paths: [STAR] },
  { selectors: ["#star-button[starred]"], paths: [STAR], filled: true },
  {
    selectors: ["#library-button", "#bookmarks-menu-button"],
    paths: ["M5 4v16", "M9.5 4v16", "M13.5 5l4.5 15"],
  },
  {
    selectors: ["#fxa-toolbar-menu-button"],
    paths: [
      "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z",
      "M4.5 20a7.5 7.5 0 0 1 15 0",
    ],
  },
];

export function iconToDataURI(icon: ArchwaveIcon): string {
  const paint = icon.filled
    ? `fill="context-fill" fill-opacity="context-fill-opacity" stroke="context-fill" stroke-width="1"`
    : `fill="none" stroke="context-fill" stroke-opacity="context-fill-opacity" stroke-width="1.6"`;
  const body = icon.paths.map((d) => `<path d="${d}"/>`).join("");
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" ${paint} stroke-linecap="round" stroke-linejoin="round">${body}</svg>`;
  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

/** `list-style-image` rules for every icon, in declaration order. */
export function buildIconCSS(
  icons: readonly ArchwaveIcon[] = ARCHWAVE_ICONS,
): string {
  return icons
    .map((icon) =>
      `${icon.selectors.join(",\n")} {\n  list-style-image: url("${
        iconToDataURI(icon)
      }") !important;\n}`
    )
    .join("\n");
}
