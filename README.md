# iPerf for Android

An ad-free, tracker-free **iperf3 client** for Android. Pick a server, run an upload, download or
bidirectional TCP/UDP test, watch a live graph, and keep a searchable history of results on your phone.
Optional support for **iperf3 Manager** servers lets you start, stop and reserve iperf3 instances
(ports) before testing.

> Unofficial Android front-end for [iperf3](https://github.com/esnet/iperf). Not affiliated with or
> endorsed by ESnet, Lawrence Berkeley National Laboratory or Google. Android is a trademark of Google LLC.

- **Package:** `local.net.iperf` · **Version:** 0.2.0 · **minSdk** 29 (Android 10) · **targetSdk** 35 (Android 15)
- **Stack:** Kotlin, Jetpack Compose, Material 3, coroutines; bundled iperf3 3.22 (+ OpenSSL 3.5.9)
- **Licence:** Apache-2.0

## Screenshots

Screenshots are rendered from example data by the Robolectric tests into `dist/screens/`
(run `./gradlew :app:testDebugUnitTest`). They are attached to GitHub Releases rather than committed.

## Features

- **Servers** – host (name, IPv4 or IPv6), port and label. A one-off port can be used for a single run without changing the saved one.
- **Test options** – TCP or UDP; upload, download (`-R`) or both (`--bidir`); duration (0 = run until Stop); parallel streams (`-P`); UDP target rate (`-b`); omit seconds (`-O`).
- **Live run** – per-second graph and readout. Tests keep running in the background with a notification that has a Stop button.
- **Results** – sender/receiver rates per direction, data transferred, average/peak/lowest rate, TCP retransmits, UDP jitter and loss.
- **History** – every finished, stopped or failed test with its network context (Wi-Fi band, signal, link speed, SSID if allowed; or cellular type). Export as JSON or CSV.
- **Clear errors** – refused, timeout, busy server, DNS failure, dropped connection and login problems, each explained in plain language with iperf3's raw message.
- **Server logins** – optional iperf3 RSA login and webpage login, encrypted with an Android Keystore key.
- **iperf3 Manager API** (optional, per server) – list instances, Start/Stop/Restart, Reserve/Extend/Release ports, Start & test, port search and filters, logs, and a local activity log.
- **Fold-aware layout** – on an unfolded foldable or tablet the tabs move to a side rail and lists show beside details; tabletop posture puts the graph above the hinge.
- **About** – version, collapsible "What's new" and an Open-source licences page.

## Permissions

| Permission | Why | When |
|---|---|---|
| `INTERNET` | Talk to the iperf3 servers and Manager APIs you add | Install time |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Label results with Wi-Fi/cellular/VPN details | Install time |
| `ACCESS_FINE_LOCATION` / `COARSE` | **Optional.** Android only reveals the Wi-Fi name (SSID) to apps with location | Asked in-app only if you choose to record the Wi-Fi name |
| `FOREGROUND_SERVICE` (+ `SPECIAL_USE`), `WAKE_LOCK` | Keep a running test alive in the background | Install time |
| `POST_NOTIFICATIONS` | The running-test notification with Stop | Asked the first time you start a test; tests run either way |

The Google advertising ID permission is explicitly removed. `READ_PHONE_STATE` is not requested.

## Privacy

No ads, no analytics, no crash reporting, no accounts. Results stay on the phone unless you export them.
The app only connects to servers you add. Saved logins are AES-256-GCM encrypted with a non-exportable
Android Keystore key, stored in `noBackupFilesDir` (excluded from backups and device transfer) and never
included in exports or logs. iperf3 passwords are passed via the `IPERF3_PASSWORD` environment variable,
never on the command line.

## How it works

```
ui/        Compose screens (Test, Servers, History, Manager, About) + AppViewModel
run/       RunController + IperfEngine: runs the bundled iperf3 and streams its JSON output
core/      Pure Kotlin: test options -> iperf3 arguments, JSON-stream parsing, results, errors
data/      JSON-file stores for servers, history, activity log, and the encrypted secret store
manager/   HTTP client and controllers for the iperf3 Manager API (reserve, start & test, search)
service/   Foreground service that keeps a test running and shows its notification
net/       Network probe that labels results (Wi-Fi band, RSSI, cellular generation, VPN)
```

The iperf3 executable ships as `app/src/main/jniLibs/<abi>/libiperf3.so`, because Android 10+ only
lets apps `exec()` files from `nativeLibraryDir` and only extracts `lib*.so` files there
(`useLegacyPackaging = true`). The app runs it with
`-c HOST -p PORT -t SECS -i 1 --json-stream --forceflush`, parses each JSON line for the live graph, and
sends SIGTERM for Stop (iperf3 then reports a partial result).

## Building

Requirements: **JDK 21** (17+ works), Android SDK Platform 35 and Build-Tools, with `sdk.dir` in a
git-ignored `local.properties` (or `ANDROID_HOME`).

```bash
./gradlew :app:testDebugUnitTest   # unit, Robolectric and screenshot tests
./gradlew :app:assembleDebug       # debug APK (application id suffix .debug)
./gradlew :app:assembleRelease     # R8-minified release APK
```

**Signing your own release:** copy `keystore.properties.example` to `keystore.properties` (git-ignored),
create a keystore with `keytool`, and fill in the four values. Without it the release APK is unsigned.
Never commit keystores or passwords.

**Rebuilding iperf3 (optional):** `./native/build-iperf3.sh` clones esnet/iperf at tag 3.22 (and checks
the pinned commit), downloads OpenSSL 3.5.9 (SHA-256 checked), and cross-compiles both with NDK
26.3.11579264 (`ANDROID_NDK_HOME`, default `~/Android/Sdk/ndk/26.3.11579264`) for arm64-v8a, armeabi-v7a
and x86_64.

## Licence

Copyright 2026 The project authors. Licensed under the [Apache License 2.0](LICENSE).

## Third-party notices

See [NOTICE](NOTICE). In short: iperf3 (BSD-3-Clause, includes cJSON under MIT), OpenSSL (Apache-2.0),
AndroidX/Jetpack Compose/Material (Apache-2.0), Kotlin and kotlinx.coroutines (Apache-2.0). The full
texts are in `app/src/main/assets/licenses/` and on the in-app Licences page.
