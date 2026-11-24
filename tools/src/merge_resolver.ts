// SPDX-License-Identifier: MPL-2.0

/**
 * Conflict fallback for archwave-update: hands a stopped `git merge` to
 * headless Claude Code and accepts the result only if it touched nothing but
 * the conflicted files and left no conflict markers. Throws otherwise; the
 * caller then aborts the merge.
 */

import * as path from "@std/path";
import { runCommand, runCommandChecked } from "./utils.ts";

/** Never auto-resolved: a wrong merge here silently drops a privacy pref. */
const PROTECTED = ["static/gecko/pref/override.ini"];
const CLAUDE_TIMEOUT_SEC = "900";

export function protectedConflicts(files: string[]): string[] {
  return files.filter((file) => PROTECTED.includes(file));
}

export function hasConflictMarkers(text: string): boolean {
  return /^(<{7}|>{7})( |$)/m.test(text);
}

/** Resolves and stages every conflicted file, or throws with the reason. */
export function resolveConflicts(root: string): string[] {
  const status = () =>
    runCommand("git", ["status", "--porcelain"], root).stdout;
  const before = status();
  const unmerged = before.split("\n").filter((line) =>
    /^(DD|AU|UD|UA|DU|AA|UU) /.test(line)
  );
  if (unmerged.length === 0) {
    throw new Error("the merge failed without conflicted files");
  }
  // shortcut: both-modified conflicts only; delete/rename conflicts have no
  // markers to verify, so they stay manual. Upgrade if those become common.
  if (unmerged.some((line) => !line.startsWith("UU "))) {
    throw new Error("a file was deleted or added on one side");
  }
  const files = unmerged.map((line) => line.slice(3));
  const blocked = protectedConflicts(files);
  if (blocked.length > 0) {
    throw new Error(`${blocked.join(", ")} is never auto-resolved`);
  }

  const prompt =
    "A `git merge` of upstream (MERGE_HEAD) into this fork (HEAD) stopped " +
    `with conflicts in:\n${files.join("\n")}\n\n` +
    "Resolve every conflict marker in exactly those files so that upstream's " +
    "changes and the fork's local customisations both survive. " +
    "`git diff HEAD...MERGE_HEAD -- <file>` shows what upstream changed and " +
    "`git diff MERGE_HEAD...HEAD -- <file>` what the fork changed. " +
    "Edit no other file and do not stage or commit. If you are not confident " +
    "about a file, leave its markers in place: a human will then merge it.";
  const claude = runCommandChecked("timeout", [
    CLAUDE_TIMEOUT_SEC,
    "claude",
    "-p",
    prompt,
    "--permission-mode",
    "acceptEdits",
    "--allowedTools",
    "Read",
    "Edit",
    "Grep",
    "Glob",
    "Bash(git diff:*)",
    "Bash(git log:*)",
    "Bash(git show:*)",
  ], root);
  if (!claude.success) {
    throw new Error(`claude exited ${claude.code}: ${claude.stderr.trim()}`);
  }

  if (status() !== before) {
    throw new Error("Claude changed files outside the conflict");
  }
  const unresolved = files.filter((file) =>
    hasConflictMarkers(Deno.readTextFileSync(path.join(root, file)))
  );
  if (unresolved.length > 0) {
    throw new Error(`Claude left conflicts in ${unresolved.join(", ")}`);
  }
  runCommand("git", ["add", "--", ...files], root);
  return files;
}
