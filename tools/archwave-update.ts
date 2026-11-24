// SPDX-License-Identifier: MPL-2.0

/**
 * One-command update for the local archwave Floorp install (Linux only):
 *
 *   1. fetch Floorp-Projects/Floorp (remote "upstream") and merge it into the
 *      current branch, keeping uncommitted changes (stash -> merge -> pop),
 *   2. assemble a standalone browser: locked release Runtime + production
 *      assets, pages bundled statically (no dev servers),
 *   3. install it to ~/.local/opt/floorp-archwave (previous build kept as
 *      floorp-archwave.prev) and re-apply the archwave app icon.
 *
 * Usage: deno task archwave:update [--no-pull] [--no-install] [--auto-resolve]
 *                                  [--package [--release-name NAME]]
 *
 * --package also writes the browser, laid out as installed, to
 * _dist/release/floorp-archwave-<NAME>-linux-<arch>.tar.xz plus a .sha256
 * file (NAME defaults to the package.json version).
 *
 * --auto-resolve lets headless Claude Code resolve merge conflicts (see
 * tools/src/merge_resolver.ts); the merge is undone if the build then fails.
 */

import * as path from "@std/path";
import { parseArgs } from "@std/cli/parse-args";
import * as Initializer from "./src/initializer.ts";
import * as Patcher from "./src/patcher.ts";
import * as CustomAppIcons from "./src/custom_app_icons.ts";
import * as Pref from "../static/gecko/pref/pref.ts";
import * as Symlinker from "./src/symlinker.ts";
import * as Update from "./src/update.ts";
import * as Builder from "./src/builder.ts";
import * as Injector from "./src/injector.ts";
import { resolveConflicts } from "./src/merge_resolver.ts";
import { BIN_DIR, PROJECT_ROOT } from "./src/defines.ts";
import {
  exists,
  Logger,
  runCommand,
  runCommandChecked,
  safeRemove,
} from "./src/utils.ts";

const logger = new Logger("archwave-update");

const UPSTREAM_URL = "https://github.com/Floorp-Projects/Floorp.git";
const UPSTREAM_BRANCH = "upstream/main";
const ICON_DIR = path.join(PROJECT_ROOT, "static", "gecko", "archwave-icon");
const ICON_SIZES = [16, 32, 48, 64, 128];

function git(...args: string[]): string {
  return runCommand("git", args, PROJECT_ROOT).stdout.trim();
}

/** Returns true when the merge only went through thanks to --auto-resolve. */
function pullUpstream(autoResolve: boolean): boolean {
  const remotes = git("remote").split("\n");
  if (!remotes.includes("upstream")) {
    logger.info(`Adding remote "upstream" -> ${UPSTREAM_URL}`);
    git("remote", "add", "upstream", UPSTREAM_URL);
  }
  git("fetch", "upstream");

  const behind = Number(git("rev-list", "--count", `HEAD..${UPSTREAM_BRANCH}`));
  if (behind === 0) {
    logger.info("Already up to date with upstream.");
    return false;
  }
  logger.info(`${behind} new upstream commit(s); merging.`);

  const dirty = git("status", "--porcelain") !== "";
  if (dirty) git("stash", "push", "-u", "-m", "archwave-update autostash");

  const merge = runCommandChecked(
    "git",
    ["merge", "--no-edit", UPSTREAM_BRANCH],
    PROJECT_ROOT,
  );
  let autoResolved = false;
  if (!merge.success) {
    try {
      if (!autoResolve) throw new Error("--auto-resolve not given");
      const files = resolveConflicts(PROJECT_ROOT);
      git("commit", "--no-edit");
      // The auto-update script greps the log for this line.
      logger.info(
        `Auto-resolved conflicts with Claude in: ${files.join(", ")}`,
      );
      autoResolved = true;
    } catch (reason) {
      runCommandChecked("git", ["merge", "--abort"], PROJECT_ROOT);
      if (dirty) git("stash", "pop");
      throw new Error(
        `Merging ${UPSTREAM_BRANCH} conflicts with local commits; resolve it manually.\n` +
          `Not auto-resolved: ${
            reason instanceof Error ? reason.message : String(reason)
          }\n${merge.stdout}${merge.stderr}`,
      );
    }
  }

  if (dirty) {
    const pop = runCommandChecked("git", ["stash", "pop"], PROJECT_ROOT);
    if (!pop.success) {
      throw new Error(
        "Upstream merged, but your uncommitted changes conflict with it. " +
          "They are still in `git stash list`; resolve the conflicts, then " +
          `run with --no-pull.\n${pop.stdout}${pop.stderr}`,
      );
    }
  }
  logger.success("Merged upstream.");
  return autoResolved;
}

async function assemble(): Promise<void> {
  await Initializer.run();
  CustomAppIcons.run();
  Patcher.run("apply");
  Pref.run();
  Symlinker.run();
  await Builder.run("production", Update.generateUuidV7());
  // "stage" layout with static pages: everything loads from chrome:// URLs.
  Injector.run("stage");
  await Injector.injectXhtmlFromTs({
    devPages: false,
    allowBrowserHttpLoader: false,
  });
  logger.success("Assembled standalone browser.");
}

function installDir(): string {
  const home = Deno.env.get("HOME");
  if (!home) throw new Error("HOME is not set.");
  return path.join(home, ".local", "opt", "floorp-archwave");
}

/** Replacing files under a running Gecko crashes it, so refuse up front. */
function assertNotRunning(target: string): void {
  const running = runCommandChecked("pgrep", ["-f", `^${target}/`]);
  if (running.success) {
    throw new Error(
      "Floorp is running from the install directory; close it and rerun.",
    );
  }
}

/** Copies the assembled browser to `target` as a self-contained install. */
function stage(target: string): void {
  Deno.mkdirSync(path.dirname(target), { recursive: true });
  // -L resolves the dev symlinks so the install does not depend on the repo.
  runCommand("cp", ["-rL", BIN_DIR, target]);

  // Gecko's own updater only knows official Floorp releases: here it fails and
  // points at floorp.app. floorp-update.timer updates this build instead.
  const policyDir = path.join(target, "distribution");
  Deno.mkdirSync(policyDir, { recursive: true });
  Deno.writeTextFileSync(
    path.join(policyDir, "policies.json"),
    JSON.stringify({ policies: { DisableAppUpdate: true } }),
  );

  const iconDir = path.join(target, "browser", "chrome", "icons", "default");
  const backupDir = path.join(iconDir, "orig");
  Deno.mkdirSync(backupDir, { recursive: true });
  for (const size of ICON_SIZES) {
    const name = `default${size}.png`;
    const icon = path.join(ICON_DIR, name);
    if (!exists(icon)) continue;
    Deno.copyFileSync(path.join(iconDir, name), path.join(backupDir, name));
    Deno.copyFileSync(icon, path.join(iconDir, name));
  }
}

function install(): void {
  const target = installDir();
  const previous = `${target}.prev`;
  assertNotRunning(target);

  if (exists(target)) {
    safeRemove(previous);
    Deno.renameSync(target, previous);
  }
  stage(target);
  logger.success(`Installed to ${target}`);
  if (exists(previous)) logger.info(`Previous build kept at ${previous}`);
}

/** Writes the release archive and its checksum; returns the archive path. */
function packageRelease(name: string): string {
  const releaseDir = path.join(PROJECT_ROOT, "_dist", "release");
  safeRemove(releaseDir);
  stage(path.join(releaseDir, "floorp-archwave"));
  const arch = Deno.build.arch === "aarch64" ? "aarch64" : "x86_64";
  const archive = `floorp-archwave-${name}-linux-${arch}.tar.xz`;
  runCommand("tar", ["-cJf", archive, "floorp-archwave"], releaseDir);
  const sum = runCommand("sha256sum", [archive], releaseDir).stdout;
  Deno.writeTextFileSync(path.join(releaseDir, `${archive}.sha256`), sum);
  safeRemove(path.join(releaseDir, "floorp-archwave"));
  logger.success(`Packaged ${path.join(releaseDir, archive)}`);
  return archive;
}

async function main(): Promise<void> {
  if (Deno.build.os !== "linux") {
    throw new Error("archwave-update only supports Linux.");
  }
  const args = parseArgs(Deno.args, {
    boolean: ["pull", "install", "auto-resolve", "package"],
    string: ["release-name"],
    default: { pull: true, install: true },
    negatable: ["pull", "install"],
  });

  let releaseName: string | undefined;
  if (args.package) {
    const pkg = JSON.parse(
      Deno.readTextFileSync(path.join(PROJECT_ROOT, "package.json")),
    ) as { version: string };
    releaseName = args["release-name"] || pkg.version;
    // The name ends up in a file name; keep it to tag-like characters.
    if (!/^[\w.+-]+$/.test(releaseName)) {
      throw new Error(`Bad release name: ${releaseName}`);
    }
  }
  if (args.install) assertNotRunning(installDir());
  const before = git("rev-parse", "HEAD");
  const autoResolved = args.pull && pullUpstream(args["auto-resolve"]);
  try {
    await assemble();
  } catch (error) {
    // An auto-resolved merge that does not build must not stick, or every
    // later run rebuilds the same broken tree. --keep preserves local edits.
    if (autoResolved) git("reset", "--keep", before);
    throw error;
  }
  if (args.install) install();
  if (releaseName) packageRelease(releaseName);
}

try {
  await main();
} catch (error) {
  logger.error(error instanceof Error ? error.message : String(error));
  Deno.exit(1);
}
