# iPerf for Android changelog
# Newest first. Add a "## <version> — <date>" header and "- " bullets for each release;
# the About screen reads this file as-is.

## 0.2.0 — 2026-11-21
- Renamed from Iperf Local to iPerf for Android. It's the same app: it updates in place and keeps your servers, logins and history
- Now open source under the Apache License 2.0
- Start & test now starts the port before reserving it. Some iperf3 managers refuse to start a port that is reserved, even by the phone that reserved it, so the start used to fail. If the start fails now, nothing is left reserved
- The bundled iperf3 3.22 was rebuilt reproducibly from pinned sources, with no build-machine paths inside
- Open-source licences is now a short list of components with their licence types; tap one to read its full licence on its own page

## 0.1.4 — 2026-11-19
- Fold-aware layout: on an unfolded Galaxy Fold (or any tablet-size window) the tabs move to a side rail, the Test screen shows the settings beside a larger live graph, and History and the server manager show the list beside the selected item. Opening or closing the phone keeps your place, typed values and any running test
- Half-folded like a laptop (tabletop), the graph sits on the top half and the controls on the bottom, with nothing on the crease
- Reserved ports show the time left, with Release and Extend 15 min buttons (Extend is hidden for a manager that refuses to extend a held port)
- Release all mine releases every port this phone reserved on that server, after a confirmation
- While a test runs on a port this phone reserved, its notification has a Release button (the test keeps running)
- Port search on the manager screen: type a port (5250) or a range (5250-5260), and filter by Free, Reserved or Running. It works on the loaded list, so it's instant
- Every reserve, extend and release, including from the notification, is recorded in the activity log
- What's new now shows the latest version open, with older versions collapsed; tap one to expand it

## 0.1.3 — 2026-11-17
- Start & test now loads the port onto the Test tab as soon as it accepts connections, even if the manager still lists the instance as stopped. A failure is shown at the top of the manager and as a snackbar, and is not hidden if you have scrolled down the list
- Tests that fail or are stopped early are saved to History, marked failed or stopped, with the reason and any partial results from before it dropped
- The results screen, History and the CSV/JSON export now include the total data sent and received, plus the average, peak and lowest rate (TCP retransmits and UDP jitter and packet loss were already recorded)

## 0.1.2 — 2026-11-17
- Endless tests: set the duration to 0 and the test runs until you press Stop, with an elapsed clock, a graph of the last 5 minutes, and a full summary when you stop
- Tests keep running in the background, with a notification showing the live rate, elapsed time and a Stop button (notification permission is asked the first time you start a test; tests run either way)
- Activity log for each managed server: every start, stop, restart, start all, stop all, reserve, release and Start & test step from this phone, with time, ports and the result or error; export as CSV or JSON, or clear it. Logins are never recorded
- Start & test on a stopped instance (or Reserve & test on a running one): reserves the port, starts it, waits until it accepts connections, loads it on the Test tab and, if you like, starts the test. Afterwards the port is released and you're asked whether to stop the instance
- Open-source licences moved to their own page, linked from About
- Now targets Android 15 (API 35)

## 0.1.1 — 2026-11-17
- Port prompt when you pick a saved server: use its default port or another port just for this run (the saved port isn't changed)
- Optional server webpage on each server, with an Open webpage button
- Optional webpage login (username + password) with copy buttons; copied values clear from the clipboard after about a minute
- Optional iperf3 server login (username, password, RSA public key — paste or import a file); the password is passed to iperf3 privately, never on the command line
- iperf3 is now built with OpenSSL 3.5.9 so server logins work
- iperf3 Manager API support: see a server's instances and their status, start / stop / restart them, view logs with live refresh, and start or stop all (with confirmation)
- With a manager configured, the port prompt lists free ports and can reserve the one you pick for 15 min; it's released after the test
- Saved logins are encrypted with an Android Keystore key, excluded from backups and never included in exports
- About page now shows the version and this changelog

## 0.1.0 — 2026-11-17
- First release: iperf3 3.22 client bundled for arm64, armv7 and x86_64
- Saved servers (host, port, label)
- TCP and UDP; upload, download (-R) and bidirectional tests
- Duration, parallel streams, UDP target bandwidth and omit seconds
- Live throughput graph, then a summary with retransmits, jitter and loss
- History of every result, with JSON and CSV export
- Network details on each result (Wi-Fi name, band, signal, link speed, or mobile network)
