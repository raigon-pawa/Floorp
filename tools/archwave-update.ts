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
 * Usage: deno task archwave:update [--no-pull] [--no-install]
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

function pullUpstream(): void {
  const remotes = git("remote").split("\n");
  if (!remotes.includes("upstream")) {
    logger.info(`Adding remote "upstream" -> ${UPSTREAM_URL}`);
    git("remote", "add", "upstream", UPSTREAM_URL);
  }
  git("fetch", "upstream");

  const behind = Number(git("rev-list", "--count", `HEAD..${UPSTREAM_BRANCH}`));
  if (behind === 0) {
    logger.info("Already up to date with upstream.");
    return;
  }
  logger.info(`${behind} new upstream commit(s); merging.`);

  const dirty = git("status", "--porcelain") !== "";
  if (dirty) git("stash", "push", "-u", "-m", "archwave-update autostash");

  const merge = runCommandChecked(
    "git",
    ["merge", "--no-edit", UPSTREAM_BRANCH],
    PROJECT_ROOT,
  );
  if (!merge.success) {
    runCommandChecked("git", ["merge", "--abort"], PROJECT_ROOT);
    if (dirty) git("stash", "pop");
    throw new Error(
      `Merging ${UPSTREAM_BRANCH} conflicts with local commits; resolve it manually.\n${merge.stdout}${merge.stderr}`,
    );
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

function install(): void {
  const target = installDir();
  const optDir = path.dirname(target);
  const previous = `${target}.prev`;
  assertNotRunning(target);

  Deno.mkdirSync(optDir, { recursive: true });
  if (exists(target)) {
    safeRemove(previous);
    Deno.renameSync(target, previous);
  }
  // -L resolves the dev symlinks so the install does not depend on the repo.
  runCommand("cp", ["-rL", BIN_DIR, target]);

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

  logger.success(`Installed to ${target}`);
  if (exists(previous)) logger.info(`Previous build kept at ${previous}`);
}

async function main(): Promise<void> {
  if (Deno.build.os !== "linux") {
    throw new Error("archwave-update only supports Linux.");
  }
  const args = parseArgs(Deno.args, {
    boolean: ["pull", "install"],
    default: { pull: true, install: true },
    negatable: ["pull", "install"],
  });

  if (args.install) assertNotRunning(installDir());
  if (args.pull) pullUpstream();
  await assemble();
  if (args.install) install();
}

try {
  await main();
} catch (error) {
  logger.error(error instanceof Error ? error.message : String(error));
  Deno.exit(1);
}
