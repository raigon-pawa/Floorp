#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
"""Drive the app on an emulator or device over adb.

Usage: tools/emu.py [-s SERIAL] [-p PACKAGE] <command> [args]

  dump [--all]                 list on-screen nodes: centre, flags, text | description | id
  tap <query> [--nth N] [--long]
                               tap the node whose text, description or id matches <query>
  tapxy <x> <y> [--long]       tap raw coordinates
  wait <query> [--timeout S] [--gone]
                               wait until a node matches (or, with --gone, stops matching)
  has <query>                  exit 0 if a node matches, 1 otherwise
  text <string>                type into the focused field
  key <name>                   back, home, enter, menu, tab, del, search, app_switch, or KEYCODE_*
  swipe <up|down|left|right> [--dist PX] [--at X,Y]
  shot <file.png>              save a screenshot
  open <url>                   send a VIEW intent for <url> to the app
  launch | stop | clear        start, force-stop, or clear the data of the app
  capture-start                start a packet capture inside the guest (needs a rootable image)
  capture-stop <file.pcap>     stop it and save the capture; read it with tools/netaudit.py

<query> matches, case-insensitively, a node's text, content description or resource id (the part
after ":id/"). An exact match wins over a substring match. SERIAL defaults to $ANDROID_SERIAL, or to the
only connected device (with several connected, one must be named); PACKAGE to $ARCHWAVE_PKG or the debug build's application id.
"""

import argparse
import os
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

DEFAULT_PACKAGE = "io.github.raigonpawa.archwave.debug"
CAPTURE_FILE = "/data/local/tmp/archwave-capture.pcap"
KEYS = {
    "back": 4, "home": 3, "enter": 66, "menu": 82, "tab": 61, "del": 67, "search": 84,
    "app_switch": 187, "escape": 111, "up": 19, "down": 20, "left": 21, "right": 22,
}


def adb_path():
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Android/Sdk")
    candidate = os.path.join(sdk, "platform-tools", "adb")
    return candidate if os.path.exists(candidate) else "adb"


class Device:
    def __init__(self, serial, package):
        self.base = [adb_path(), "-s", serial]
        self.package = package

    def run(self, *args, binary=False, check=True):
        result = subprocess.run(self.base + list(args), capture_output=True, timeout=120)
        if check and result.returncode != 0:
            sys.exit(f"adb {' '.join(args)} failed: {result.stderr.decode(errors='replace').strip()}")
        return result.stdout if binary else result.stdout.decode(errors="replace")

    def shell(self, command, check=True):
        return self.run("shell", command, check=check)

    def nodes(self):
        """Returns the on-screen accessibility nodes, retrying while the UI is not idle."""
        for _ in range(4):
            raw = self.run("exec-out", "uiautomator", "dump", "/dev/tty", check=False)
            end = raw.find("</hierarchy>")
            if end != -1:
                root = ET.fromstring(raw[raw.find("<?xml"):end + len("</hierarchy>")])
                return [Node(element) for element in root.iter("node")]
            time.sleep(1)
        sys.exit("Could not dump the UI (screen never became idle)")

    def find(self, query, include_hidden=False):
        """Exact matches first, then substring matches; only nodes with a visible area."""
        wanted = query.lower()
        candidates = [n for n in self.nodes() if include_hidden or n.area > 0]
        exact = [n for n in candidates if wanted in (v.lower() for v in n.labels())]
        if exact:
            return exact
        return [n for n in candidates if any(wanted in v.lower() for v in n.labels())]

    def tap(self, x, y, long=False):
        if long:
            self.shell(f"input swipe {x} {y} {x} {y} 900")
        else:
            self.shell(f"input tap {x} {y}")

    def size(self):
        match = re.search(r"(\d+)x(\d+)", self.shell("wm size"))
        return int(match.group(1)), int(match.group(2))


class Node:
    def __init__(self, element):
        get = element.attrib.get
        self.text = get("text", "")
        self.desc = get("content-desc", "")
        self.rid = get("resource-id", "").rpartition(":id/")[2]
        self.cls = get("class", "").rpartition(".")[2]
        self.flags = "".join(
            letter for letter, name in (("c", "clickable"), ("k", "checked"), ("f", "focused"), ("s", "selected"))
            if get(name) == "true"
        ) + ("" if get("enabled", "true") == "true" else "d")
        x1, y1, x2, y2 = map(int, re.findall(r"-?\d+", get("bounds", "[0,0][0,0]")))
        self.centre = ((x1 + x2) // 2, (y1 + y2) // 2)
        self.area = max(0, x2 - x1) * max(0, y2 - y1)

    def labels(self):
        return [value for value in (self.text, self.desc, self.rid) if value]

    def __str__(self):
        parts = " | ".join(value.replace("\n", " ")[:90] for value in self.labels())
        return f"{self.centre[0]:>5},{self.centre[1]:<5} {self.flags:<3} {self.cls:<18} {parts}"


def pick(device, query, nth):
    matches = device.find(query)
    if not matches:
        visible = "\n".join(str(n) for n in device.nodes() if n.labels() and n.area > 0)
        sys.exit(f"No node matches {query!r}. On screen:\n{visible}")
    if nth >= len(matches):
        sys.exit(f"Only {len(matches)} node(s) match {query!r}")
    return matches[nth]


def only_device():
    """The serial of the single connected device; refuses to guess when there are several."""
    listing = subprocess.run([adb_path(), "devices"], capture_output=True, text=True, timeout=30).stdout
    serials = [line.split()[0] for line in listing.splitlines()[1:] if line.strip().endswith("device")]
    if len(serials) != 1:
        sys.exit(f"Pass -s SERIAL or set ANDROID_SERIAL: {len(serials)} devices connected ({', '.join(serials)})")
    return serials[0]


def escape_input_text(value):
    # `input text` takes %s for a space; everything else is quoted for the device shell.
    return shlex.quote(value.replace("%", "%%").replace(" ", "%s"))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("-s", "--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("-p", "--package", default=os.environ.get("ARCHWAVE_PKG", DEFAULT_PACKAGE))
    parser.add_argument("command")
    parser.add_argument("args", nargs="*")
    parser.add_argument("--all", action="store_true", help="dump: include nodes without a label")
    parser.add_argument("--nth", type=int, default=0, help="tap: which match to use (0-based)")
    parser.add_argument("--long", action="store_true", help="tap: long press")
    parser.add_argument("--timeout", type=float, default=20, help="wait: seconds")
    parser.add_argument("--gone", action="store_true", help="wait: until nothing matches")
    parser.add_argument("--dist", type=int, default=900, help="swipe: distance in pixels")
    parser.add_argument("--at", default=None, help="swipe: start point as X,Y")
    options = parser.parse_args()
    if not options.serial:
        options.serial = only_device()
    device = Device(options.serial, options.package)
    command, args = options.command, options.args

    def need(count):
        if len(args) < count:
            sys.exit(f"{command}: expected {count} argument(s)\n\n{__doc__}")

    if command == "dump":
        for node in device.nodes():
            if node.area > 0 and (options.all or node.labels()):
                print(node)
    elif command == "tap":
        need(1)
        node = pick(device, args[0], options.nth)
        device.tap(*node.centre, long=options.long)
        print(f"tapped {node}")
    elif command == "tapxy":
        need(2)
        device.tap(int(args[0]), int(args[1]), long=options.long)
    elif command == "has":
        need(1)
        sys.exit(0 if device.find(args[0]) else 1)
    elif command == "wait":
        need(1)
        deadline = time.monotonic() + options.timeout
        while True:
            found = bool(device.find(args[0]))
            if found != options.gone:
                return
            if time.monotonic() > deadline:
                state = "still on screen" if options.gone else "not found"
                visible = "\n".join(str(n) for n in device.nodes() if n.labels() and n.area > 0)
                sys.exit(f"Timed out after {options.timeout:g}s: {args[0]!r} {state}. On screen:\n{visible}")
            time.sleep(0.7)
    elif command == "text":
        need(1)
        device.shell(f"input text {escape_input_text(args[0])}")
    elif command == "key":
        need(1)
        code = KEYS.get(args[0].lower(), args[0])
        device.shell(f"input keyevent {code}")
    elif command == "swipe":
        need(1)
        width, height = device.size()
        x, y = map(int, options.at.split(",")) if options.at else (width // 2, height // 2)
        dx, dy = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}[args[0]]
        device.shell(f"input swipe {x} {y} {x + dx * options.dist} {y + dy * options.dist} 300")
    elif command == "shot":
        need(1)
        with open(args[0], "wb") as output:
            output.write(device.run("exec-out", "screencap", "-p", binary=True))
        print(args[0])
    elif command == "open":
        need(1)
        url = shlex.quote(args[0])
        print(device.shell(f"am start -W -a android.intent.action.VIEW -d {url} {device.package}").strip())
    elif command == "launch":
        device.shell(f"monkey -p {device.package} -c android.intent.category.LAUNCHER 1")
    elif command == "stop":
        device.shell(f"am force-stop {device.package}")
    elif command == "clear":
        print(device.shell(f"pm clear {device.package}").strip())
    elif command == "capture-start":
        device.run("root", check=False)
        device.run("wait-for-device")
        device.shell(f"pkill tcpdump; rm -f {CAPTURE_FILE}", check=False)
        # -U flushes each packet, so stopping the capture loses nothing.
        device.shell(f"nohup tcpdump -i any -U -w {CAPTURE_FILE} >/dev/null 2>&1 &")
        print("capturing")
    elif command == "capture-stop":
        need(1)
        device.shell("pkill -INT tcpdump", check=False)
        time.sleep(1)
        device.run("pull", CAPTURE_FILE, args[0])
        print(args[0])
    else:
        sys.exit(f"Unknown command {command!r}\n\n{__doc__}")


if __name__ == "__main__":
    main()
