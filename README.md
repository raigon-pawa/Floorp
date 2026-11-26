# Archwave for Android

A monochrome, privacy-hardened Android browser with Firefox extension support. It is the Android
counterpart of the desktop [Floorp Archwave](https://github.com/raigon-pawa/Floorp) build.

> [!NOTE]
> Archwave is an **unofficial, independent fork** of Mozilla's
> [Reference Browser](https://github.com/mozilla-mobile/reference-browser), built on
> [Android Components](https://mozac.org) and GeckoView. It is not affiliated with or endorsed by
> Mozilla. Licensed under the [Mozilla Public License 2.0](LICENSE).

## Features

- **Firefox extensions.** Install from the built-in add-ons manager, from addons.mozilla.org, or
  from a signed `.xpi` file. Extension actions, with their badges, are in the menu's Extensions
  submenu. Popups and options pages work, extensions ask before getting extra site access, and
  updates are checked daily.
- **One dark monochrome look.** Black, grey and white across every screen and dialog, a native
  start page with ArchWiki, Wikipedia, GitHub and DuckDuckGo tiles (icons bundled, no network),
  and JetBrains Mono in the address bar. Error pages and the private-browsing page match.
- **Privacy defaults** (all editable under Settings > Privacy):
  - strict tracking protection in normal and private tabs
  - HTTPS-only mode
  - DNS over HTTPS through Quad9, falling back to the system resolver (Cloudflare, Mullvad or off
    are available)
  - Global Privacy Control
  - query-parameter stripping
  - no search suggestions unless turned on, and never in private tabs
  - DuckDuckGo as default search engine (others selectable)
- **Nothing sent home.** No telemetry, no crash upload, no Firebase or Google push, no Firefox
  Account or Sync, no Android cloud backup. Crashes are handled on the device, with an offer to
  restore crashed tabs.
- **Private tabs** are kept out of screenshots, Recents and the session file.
- **Clear browsing data** by category, links from other apps optionally opened in a private tab,
  and an About screen with open-source licences and a list of what the app connects to.

### What the app connects to

Without user action: Mozilla Remote Settings (blocklists and security data), Google Safe Browsing
list updates, and the DNS-over-HTTPS resolver. When used: addons.mozilla.org (add-ons manager,
extension updates), the search engine (only if search suggestions are on), and the sites you visit.
Check it yourself with `tools/netaudit.py` (see below).

## Building

Requirements: JDK 21 (`/usr/lib/jvm/java-21-openjdk` on Arch; newer JDKs are too new for this
Gradle), the Android SDK with platform 37.2 and build-tools 37, and `sdk.dir=` in
`local.properties`.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/, id io.github.raigonpawa.archwave.debug
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/ (R8-minified), id io.github.raigonpawa.archwave
```

Release builds are signed with a keystore named in `local.properties` (`archwave.storeFile`,
`archwave.storePassword`, `archwave.keyAlias`, `archwave.keyPassword`) or in the environment
(`ARCHWAVE_STORE_FILE`, `ARCHWAVE_STORE_PASSWORD`, `ARCHWAVE_KEY_ALIAS`, `ARCHWAVE_KEY_PASSWORD`).
Without one, they are signed with the debug key and Gradle prints a warning; such builds are for
testing only. Never commit a keystore.

The engine is the release channel of Android Components and GeckoView (Gecko 160), pinned in
`gradle/libs.versions.toml`.

## Testing

```bash
./gradlew ktfmtCheck detekt :app:lintDebug buildHealth   # static checks (ktfmtFormat fixes formatting)
./gradlew :app:testDebugUnitTest                         # JVM tests
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest   # instrumented UI tests
```

Run one test class with
`-Pandroid.testInstrumentationRunnerArguments.class=org.mozilla.reference.browser.ui.SearchTest`.
A connected run clears the app's data and uninstalls the debug app afterwards.

Tools for working with emulators, in `tools/`:

| Tool | What it does |
| --- | --- |
| `avd.sh start\|stop\|restart\|status <n>` | Headless test emulators `archwave_android_<n>`; `status` flags a hung or bloated emulator |
| `emu.py -s <serial> <command>` | Drive the app over adb: dump the screen, tap by text or id, type, screenshot, open URLs, capture traffic |
| `netaudit.py <capture.pcap>` | List the hosts contacted (DNS, TLS server names, QUIC) from `emu.py capture-start` / `capture-stop` |
| `chroma.py <screenshot>` | Find coloured pixels in a screenshot, to keep the UI monochrome |
| `gen_icons.py` | Regenerate the launcher and notification icons from the Archwave mark |

## Licence and credits

Archwave is distributed under the [Mozilla Public License 2.0](LICENSE). It is based on Mozilla's
Reference Browser and uses Mozilla's Android Components and GeckoView (MPL 2.0). The address-bar
font is JetBrains Mono, under the SIL Open Font License (`app/src/main/assets/licenses/`). The
full list of bundled components and their licences is in the app under Settings > About Archwave >
Open-source licences.
