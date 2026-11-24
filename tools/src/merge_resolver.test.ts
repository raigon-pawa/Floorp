// SPDX-License-Identifier: MPL-2.0

import { assertEquals } from "@std/assert";
import { hasConflictMarkers, protectedConflicts } from "./merge_resolver.ts";

Deno.test("override.ini conflicts are never auto-resolved", () => {
  assertEquals(
    protectedConflicts(["deno.json", "static/gecko/pref/override.ini"]),
    ["static/gecko/pref/override.ini"],
  );
  assertEquals(protectedConflicts(["deno.json"]), []);
});

Deno.test("leftover conflict markers are detected", () => {
  assertEquals(hasConflictMarkers("a\n<<<<<<< HEAD\nb\n"), true);
  assertEquals(hasConflictMarkers("a\n>>>>>>> upstream/main\n"), true);
  assertEquals(hasConflictMarkers("Title\n=======\nbody <<<<<<< x\n"), false);
});
