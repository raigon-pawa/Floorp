#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
#
# Local Floorp build script. Mirrors the build steps in
# .github/workflows/package.yml for Linux and macOS.
#
# Modes:
#   assets (default)  Build production Noraneko assets only (before-mach).
#   full              Assets + mach configure/build + inject (after-mach).
#
# A full build expects the CI layout: this repo at <gecko>/noraneko, with
# a mozconfig that sets MOZ_OBJDIR=./obj-artifact-build-output.
#
# Usage:
#   tools/build.sh [--full] [--gecko DIR] [--artifact FILE] [--package]
#                  [--skip-install] [--skip-runtime-patches]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="assets"
GECKO_DIR=""
ARTIFACT="${MOZ_ARTIFACT_FILE:-}"
DO_PACKAGE=false
DO_INSTALL=true
DO_RUNTIME_PATCHES=true

log() { printf '\033[1;34m[build]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[build] error:\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
  sed -n '3,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --full) MODE="full" ;;
    --gecko) GECKO_DIR="${2:?--gecko needs a directory}"; shift ;;
    --artifact) ARTIFACT="${2:?--artifact needs a file}"; shift ;;
    --package) DO_PACKAGE=true ;;
    --skip-install) DO_INSTALL=false ;;
    --skip-runtime-patches) DO_RUNTIME_PATCHES=false ;;
    -h|--help) usage 0 ;;
    *) printf 'Unknown option: %s\n\n' "$1" >&2; usage 1 ;;
  esac
  shift
done

command -v deno >/dev/null || die "deno not found in PATH"

case "$(uname -s)" in
  Linux) PLATFORM="linux" ;;
  Darwin) PLATFORM="darwin" ;;
  *) die "unsupported platform $(uname -s) (use CI for Windows builds)" ;;
esac

# Resolve the Firefox/Gecko source tree for full builds.
if [[ "$MODE" == "full" ]]; then
  if [[ -z "$GECKO_DIR" && -x "$REPO_ROOT/../mach" ]]; then
    GECKO_DIR="$REPO_ROOT/.."
  fi
  [[ -n "$GECKO_DIR" ]] || die "full build needs --gecko DIR (Firefox runtime source with ./mach)"
  GECKO_DIR="$(cd "$GECKO_DIR" && pwd)"
  [[ -x "$GECKO_DIR/mach" ]] || die "no executable mach in $GECKO_DIR"
  [[ "$(cd "$GECKO_DIR/noraneko" 2>/dev/null && pwd -P)" == "$(cd "$REPO_ROOT" && pwd -P)" ]] \
    || die "this repo must be at $GECKO_DIR/noraneko (clone or symlink it there); after-mach injects into ../obj-artifact-build-output"
  [[ -f "$GECKO_DIR/mozconfig" ]] \
    || die "no mozconfig in $GECKO_DIR (see .github/workflows/mozconfigs/; set MOZ_OBJDIR=./obj-artifact-build-output)"
  if [[ -n "$ARTIFACT" ]]; then
    for f in ${ARTIFACT//:/ }; do
      [[ -s "$f" ]] || die "artifact file missing or empty: $f"
    done
  fi
fi

cd "$REPO_ROOT"

if $DO_INSTALL; then
  log "Installing dependencies"
  deno install --allow-scripts
fi

if [[ "$MODE" == "full" ]] && $DO_RUNTIME_PATCHES; then
  log "Applying Floorp runtime patches to $GECKO_DIR"
  for patch in "$REPO_ROOT"/.github/patches/floorp-runtime/common/*.patch; do
    [[ -e "$patch" ]] || continue
    if git -C "$GECKO_DIR" apply --reverse --check "$patch" >/dev/null 2>&1; then
      log "  already applied: $(basename "$patch")"
    else
      log "  applying: $(basename "$patch")"
      git -C "$GECKO_DIR" apply "$patch"
    fi
  done
fi

log "Building production assets (before-mach)"
NODE_ENV=production deno task feles-build build --phase before-mach

if [[ "$MODE" == "assets" ]]; then
  log "Done. Assets are in $REPO_ROOT/_dist"
  exit 0
fi

log "Running mach configure"
(cd "$GECKO_DIR" && ./mach configure)

log "Running mach build${ARTIFACT:+ (artifact: $ARTIFACT)}"
if [[ -n "$ARTIFACT" ]]; then
  (cd "$GECKO_DIR" && MOZ_ARTIFACT_FILE="$ARTIFACT" ./mach build)
else
  (cd "$GECKO_DIR" && ./mach build)
fi

log "Injecting Noraneko (after-mach)"
deno task feles-build build --phase after-mach

DIST_ROOT="$GECKO_DIR/obj-artifact-build-output/dist"
BIN_DIR="$DIST_ROOT/bin"
BUILDID2="$REPO_ROOT/_dist/buildid2"

copy_buildid2() {
  [[ -f "$BUILDID2" && -d "$1" ]] || return 0
  mkdir -p "$1/browser"
  cp "$BUILDID2" "$1/browser/buildid2"
  log "  buildid2 -> $1/browser/buildid2"
}

if [[ "$PLATFORM" == "darwin" ]]; then
  shopt -s nullglob
  for app in "$DIST_ROOT"/*.app "$DIST_ROOT"/floorp/*.app; do
    [[ -d "$app/Contents/Resources" ]] && copy_buildid2 "$app/Contents/Resources"
  done
  shopt -u nullglob
else
  [[ -d "$BIN_DIR" ]] || die "mach build output not found at $BIN_DIR (check MOZ_OBJDIR in mozconfig)"

  # Replace symlinks with real files so patches don't touch the source tree.
  log "Materializing $BIN_DIR"
  rm -rf "$DIST_ROOT/tmp__bin"
  rsync -aL "$BIN_DIR/" "$DIST_ROOT/tmp__bin"
  rm -rf "$BIN_DIR"
  mv "$DIST_ROOT/tmp__bin" "$BIN_DIR"

  log "Applying tools/patches to dist/bin"
  for patch in "$REPO_ROOT"/tools/patches/*.patch; do
    [[ -e "$patch" ]] || continue
    case "$patch" in
      *.windows.patch|*.darwin.patch) continue ;;
    esac
    if git apply --reverse --directory "$BIN_DIR" --unsafe-paths --check "$patch" >/dev/null 2>&1; then
      log "  already applied: $(basename "$patch")"
    else
      git apply --directory "$BIN_DIR" --unsafe-paths "$patch"
    fi
  done

  copy_buildid2 "$BIN_DIR"
  copy_buildid2 "$DIST_ROOT/floorp"
  copy_buildid2 "$DIST_ROOT/noraneko"
fi

if $DO_PACKAGE; then
  log "Running mach package"
  (cd "$GECKO_DIR" && ./mach package)
fi

log "Done. Build output: $DIST_ROOT"
