package local.net.iperf.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isDialog
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.material3.TextButton
import local.net.iperf.core.Changelog
import local.net.iperf.core.OneTimePort
import local.net.iperf.data.Server
import local.net.iperf.data.ServerSecrets
import local.net.iperf.manager.InstanceStatus
import local.net.iperf.manager.LogUi
import local.net.iperf.manager.ManagedInstance
import local.net.iperf.manager.ManagerUi
import local.net.iperf.manager.PortAvailability
import local.net.iperf.manager.Reservation
import local.net.iperf.ui.AboutContent
import local.net.iperf.ui.LicenseText
import local.net.iperf.ui.PortPromptUi
import local.net.iperf.ui.manager.ManagerActions
import local.net.iperf.ui.manager.ManagerContent
import local.net.iperf.ui.servers.PortPromptBody
import local.net.iperf.ui.servers.portPromptTitle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import local.net.iperf.ui.servers.ServerEditContent
import local.net.iperf.ui.servers.ServerForm
import local.net.iperf.ui.servers.WebpageDialog
import local.net.iperf.SampleData
import local.net.iperf.core.Direction
import local.net.iperf.core.IntervalPoint
import local.net.iperf.core.OptionsDraft
import local.net.iperf.core.Protocol
import local.net.iperf.core.RunError
import local.net.iperf.core.TestOptions
import local.net.iperf.run.RunState
import local.net.iperf.ui.HistoryMenu
import local.net.iperf.ui.Tab
import local.net.iperf.ui.history.HistoryActions
import local.net.iperf.ui.history.HistoryContent
import local.net.iperf.ui.history.HistoryDetailContent
import local.net.iperf.ui.servers.ServerActions
import local.net.iperf.ui.servers.ServersContent
import local.net.iperf.ui.test.SsidHint
import local.net.iperf.ui.test.TestActions
import local.net.iperf.ui.test.TestContent
import local.net.iperf.ui.test.TestUi
import local.net.iperf.ui.theme.IperfTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone

/** Screens rendered with EXAMPLE data from [SampleData] — not real measurements. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h816dp-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val outDir = File(System.getProperty("screens.outDir") ?: "build/screens").apply { mkdirs() }

    @Before fun tz() { TimeZone.setDefault(TimeZone.getTimeZone("America/Denver")) }

    @Composable
    private fun Phone(dark: Boolean, title: String, tab: Tab?, back: Boolean = false, actions: @Composable () -> Unit = {
        IconButton({}) { Icon(Icons.Rounded.Info, null) }
    }, body: @Composable () -> Unit) {
        IperfTheme(dark = dark) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = { Text(title) },
                        navigationIcon = { if (back) IconButton({}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
                        actions = { actions() },
                    )
                    Box(Modifier.weight(1f)) { body() }
                    if (tab != null) NavigationBar {
                        Tab.entries.forEach { NavigationBarItem(it == tab, {}, { Icon(it.icon, null) }, label = { Text(it.label) }) }
                    }
                }
            }
        }
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent(content)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, name).absolutePath)
    }

    private val noTest = object : TestActions {}
    private val home = TestUi(
        servers = SampleData.servers, selected = SampleData.servers[0],
        draft = OptionsDraft(Protocol.TCP, Direction.UPLOAD, "10", "4", "100", "0"),
        network = SampleData.wifi.copy(ssid = null), ssidHint = SsidHint.ASK,
    )
    private val runningOpts = TestOptions("192.168.1.20", direction = Direction.BIDIR, durationSec = 20, parallel = 4)
    private val running = home.copy(
        draft = OptionsDraft(Protocol.TCP, Direction.BIDIR, "20", "4", "100", "0"),
        network = SampleData.wifi, ssidHint = SsidHint.NONE,
        run = RunState.Running(
            runningOpts, "Home NAS",
            points = SampleData.wave(12, 580.0, 45.0).zip(SampleData.wave(12, 820.0, 60.0, 1.1)).mapIndexed { i, (u, d) ->
                IntervalPoint((i + 1).toDouble(), u, d)
            },
            connected = true,
        ),
    )
    private val done = home.copy(network = SampleData.wifi, ssidHint = SsidHint.NONE, run = RunState.Done(SampleData.tcpUpload))
    private val udpDone = home.copy(
        selected = SampleData.servers[1], network = SampleData.wifiNoSsid, ssidHint = SsidHint.NONE,
        draft = OptionsDraft(Protocol.UDP, Direction.DOWNLOAD, "10", "1", "200", "0"),
        run = RunState.Done(SampleData.udpDownload),
    )
    private val failed = home.copy(ssidHint = SsidHint.NONE, run = RunState.Failed(
        RunError("Server busy", "192.168.1.20:5201 is already running a test for someone else. iperf3 servers handle one client at a time — try again in a moment.",
            "the server is busy running a test. try again later"),
        runningOpts, "Home NAS",
        SampleData.tcpUpload.copy(
            errorTitle = "Server busy",
            errorDetail = "192.168.1.20:5201 is already running a test for someone else. iperf3 servers handle one client at a time — try again in a moment.",
            summary = local.net.iperf.core.Summary(),
            points = emptyList(),
        ),
    ))

    private val failedPartial = home.copy(ssidHint = SsidHint.NONE, network = SampleData.wifi, run = RunState.Failed(
        local.net.iperf.core.RunError("Lost connection", SampleData.failedLost.errorDetail!!),
        SampleData.failedLost.options, SampleData.failedLost.serverLabel, SampleData.failedLost,
    ))

    @Composable private fun homeShot(dark: Boolean, ui: TestUi) = Phone(dark, "iPerf for Android", Tab.TEST) { TestContent(ui, noTest) }
    @Composable private fun serversShot(dark: Boolean) = Phone(dark, "Servers", Tab.SERVERS) { ServersContent(SampleData.servers, "s1", object : ServerActions {}) }
    @Composable private fun historyShot(dark: Boolean) = Phone(dark, "History", Tab.HISTORY, actions = { HistoryMenu({}, {}) }) {
        HistoryContent(SampleData.history, object : HistoryActions {})
    }
    @Composable private fun detailShot(dark: Boolean) = Phone(dark, "Result", null, back = true, actions = { IconButton({}) { Icon(Icons.Rounded.DeleteOutline, null) } }) {
        HistoryDetailContent(SampleData.bidir)
    }

    @Test fun homeLight() = shot("01-home-light.png") { homeShot(false, home) }
    @Test fun homeDark() = shot("01-home-dark.png") { homeShot(true, home) }
    @Test fun serversLight() = shot("02-servers-light.png") { serversShot(false) }
    @Test fun serversDark() = shot("02-servers-dark.png") { serversShot(true) }
    @Test fun runningLight() = shot("03-running-light.png") { homeShot(false, running) }
    @Test fun runningDark() = shot("03-running-dark.png") { homeShot(true, running) }
    @Test fun resultsLight() = shot("04-results-tcp-light.png") { homeShot(false, done) }
    @Test fun resultsDark() = shot("04-results-tcp-dark.png") { homeShot(true, done) }
    @Test fun resultsUdpLight() = shot("05-results-udp-light.png") { homeShot(false, udpDone) }
    @Test fun resultsUdpDark() = shot("05-results-udp-dark.png") { homeShot(true, udpDone) }
    @Test fun historyLight() = shot("06-history-light.png") { historyShot(false) }
    @Test fun historyDark() = shot("06-history-dark.png") { historyShot(true) }
    @Test fun detailLight() = shot("07-history-detail-light.png") { detailShot(false) }
    @Test fun detailDark() = shot("07-history-detail-dark.png") { detailShot(true) }
    @Test fun errorLight() = shot("08-error-busy-light.png") { homeShot(false, failed) }
    @Test fun errorDark() = shot("08-error-busy-dark.png") { homeShot(true, failed) }

    // ---- 0.1.1 (example data and obviously fake credentials) ----
    private val lab = Server("n1", "iperf.example.net", 5201, "Lab server", "https://iperf.example.net", managerEnabled = true)

    private fun screenShot(name: String, content: @Composable () -> Unit) {
        // Dialog windows with text fields never report idle under NATIVE graphics; drive the clock by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent(content)
        compose.mainClock.advanceTimeBy(2000)
        compose.onNode(isDialog()).captureRoboImage(File(outDir, name).absolutePath)
    }

    /** The prompt's real body in a dialog-styled card over the Test screen (Robolectric can't idle a dialog window here). */
    @Composable private fun promptOverHome(ui: PortPromptUi, behind: TestUi) {
        Box(Modifier.fillMaxSize()) {
            homeShot(true, behind)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            IperfTheme(dark = true) {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp)) {
                    Column(Modifier.padding(24.dp)) {
                        Text(portPromptTitle(ui), style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(16.dp))
                        Box(Modifier.heightIn(max = 520.dp)) { PortPromptBody(ui) { _, _ -> } }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton({}) { Text("Cancel") } }
                    }
                }
            }
        }
    }

    @Test fun portPromptDark() = shot("09-port-prompt-dark.png") {
        promptOverHome(
            PortPromptUi(lab, managed = true, ports = PortAvailability(listOf(5203, 5204, 5207, 5211, 5216, 5220, 5233, 5241), listOf(5299, 5300))),
            home.copy(ssidHint = SsidHint.NONE, selected = lab, servers = listOf(lab)),
        )
    }

    @Test fun portPromptPlainDark() = shot("09b-port-prompt-plain-dark.png") {
        promptOverHome(PortPromptUi(SampleData.servers[0]), home.copy(ssidHint = SsidHint.NONE))
    }

    @Test fun homeOneTimePortDark() = shot("09c-home-one-time-port-dark.png") {
        homeShot(true, home.copy(ssidHint = SsidHint.NONE, selected = lab, servers = listOf(lab),
            oneTime = OneTimePort("n1", 5207, reserved = true, reserveMinutes = 15)))
    }

    @Config(qualifiers = "w392dp-h1500dp-xhdpi")
    @Test fun serverEditDark() = shot("10-server-edit-dark.png") {
        val form = ServerForm(
            host = "iperf.example.net", port = "5201", label = "Lab server", webUrl = "https://iperf.example.net",
            webUser = "example-user", webPassword = "not-a-real-password", managerEnabled = true,
            iperfUser = "example-iperf", iperfPassword = "fake-iperf-pass",
            iperfKey = "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AEXAMPLEEXAMPLEEXAMPLEEXAMPLE\n-----END PUBLIC KEY-----",
        )
        Phone(true, "Edit server", null, actions = { TextButton({}) { Text("Save") } }) {
            ServerEditContent(form, {}, tried = false)
        }
    }

    @Config(qualifiers = "w392dp-h1600dp-xhdpi")
    @Test fun aboutDark() = shot("11-about-changelog-dark.png") {
        val log = Changelog.parse(listOf(File("src/main/assets/CHANGELOG.md"), File("app/src/main/assets/CHANGELOG.md")).first { it.exists() }.readText())
        Phone(true, "About", null, back = true, actions = {}) {
            AboutContent("0.1.2", 3, log)
        }
    }

    private val managerUi = ManagerUi(
        "Lab server", "https://iperf.example.net",
        instances = listOf(
            ManagedInstance(5201, InstanceStatus.RUNNING, "running", "running", "ok"),
            ManagedInstance(5202, InstanceStatus.RESERVED, "reserved", "running", "ok", Reservation("15:42", 15, "203.0.113.7")),
            ManagedInstance(5203, InstanceStatus.STOPPED, "stopped", "stopped", null),
            ManagedInstance(5204, InstanceStatus.DEGRADED, "degraded", "running", "slow accept"),
            ManagedInstance(5205, InstanceStatus.RESTARTING, "restarting", "running", null),
            ManagedInstance(5206, InstanceStatus.BLOCKED, "blocked", "stopped", null),
        ),
        ports = PortAvailability((5203..5300).filter { it != 5206 && it != 5299 }, listOf(5206, 5299)),
        loaded = true,
    )

    @Test fun managerDark() = shot("12-manager-dark.png") {
        Phone(true, "Lab server", null, back = true, actions = {}) { ManagerContent(managerUi, object : ManagerActions {}) }
    }

    @Test fun managerLogsDark() = screenShot("13-manager-logs-dark.png") {
        val lines = (1..14).map { i -> "2026-11-17 14:%02d:%02d  ".format(10 + i / 6, (i * 7) % 60) + listOf(
            "Server listening on 5201 (test #$i)", "Accepted connection from 203.0.113.7, port 51${i}22",
            "[  5] local 10.0.0.2 port 5201 connected to 203.0.113.7 port 51${i}24", "iperf Done.")[i % 4] }
        Phone(true, "Lab server", null, back = true, actions = {}) {
            ManagerContent(managerUi.copy(logs = LogUi(5201, lines, next = 14, total = 14)), object : ManagerActions {})
        }
    }

    @Test fun webpageLoginDark() = screenShot("14-webpage-login-dark.png") {
        IperfTheme(dark = true) { WebpageDialog(lab, ServerSecrets(webUser = "example-user", webPassword = "not-a-real-password"), {}, { _, _, _ -> }, {}) }
    }

    @Test fun serversWithWebpageDark() = shot("14b-servers-webpage-dark.png") {
        Phone(true, "Servers", Tab.SERVERS) {
            ServersContent(listOf(lab) + SampleData.servers, "n1", object : ServerActions {},
                secrets = mapOf("n1" to ServerSecrets(webUser = "example-user", webPassword = "not-a-real-password")))
        }
    }

    // ---- 0.1.2 ----
    private val endlessOpts = TestOptions("192.168.1.20", direction = Direction.DOWNLOAD, durationSec = 0, parallel = 4)
    private val endlessRunning = home.copy(
        draft = OptionsDraft(Protocol.TCP, Direction.DOWNLOAD, "0", "4", "100", "0"),
        network = SampleData.wifi, ssidHint = SsidHint.NONE,
        run = SampleData.wave(300, 610.0, 70.0).mapIndexed { i, d -> IntervalPoint((i + 3421).toDouble(), null, d) }.let { pts ->
            RunState.Running(endlessOpts, "Home NAS", points = pts, connected = true)
        },
    )

    @Test fun endlessRunningDark() = shot("15-endless-running-dark.png") { homeShot(true, endlessRunning) }

    private val activity = run {
        val t0 = 1_794_956_400_000L // 2026-11-17 16:00 MST, example data
        fun e(min: Int, a: local.net.iperf.data.LogAction, ports: List<Int>, ok: Boolean, detail: String?, sat: Boolean = false) =
            local.net.iperf.data.ActivityEntry("e$min", t0 + min * 60_000L + min * 7_000L, "n1", "Lab server", ports, a, ok, detail, sat)
        listOf(
            e(41, local.net.iperf.data.LogAction.STOP, listOf(5203), true, "Stopped after test", true),
            e(40, local.net.iperf.data.LogAction.RELEASE, listOf(5203), true, "Released after test", true),
            e(39, local.net.iperf.data.LogAction.TEST_END, listOf(5203), true, "Stopped · down 612 Mbps", true),
            e(20, local.net.iperf.data.LogAction.TEST_START, listOf(5203), true, "TCP · Download · endless · 4 streams", true),
            e(19, local.net.iperf.data.LogAction.LOAD_PORT, listOf(5203), true, "Loaded as this run's port", true),
            e(18, local.net.iperf.data.LogAction.WAIT_REACHABLE, listOf(5203), true, "Reachable after 3 s", true),
            e(17, local.net.iperf.data.LogAction.START, listOf(5203), true, "Started", true),
            e(16, local.net.iperf.data.LogAction.RESERVE, listOf(5203), true, "Reserved for 15 min", true),
            e(9, local.net.iperf.data.LogAction.START, listOf(5280), false, "The manager hit an error (HTTP 500) (port in use)."),
            e(5, local.net.iperf.data.LogAction.START_ALL, (5201..5210).toList(), false, "9 started, 1 failed (5206: blocked)"),
            e(2, local.net.iperf.data.LogAction.RESTART, listOf(5201), true, "Restarted"),
        )
    }

    @Config(qualifiers = "w392dp-h1000dp-xhdpi")
    @Test fun activityLogDark() = shot("17-activity-log-dark.png") {
        Phone(true, "Activity log", null, back = true, actions = { local.net.iperf.ui.manager.ActivityLogMenu(true, {}, {}) }) {
            local.net.iperf.ui.manager.ActivityLogContent("Lab server", activity, zone = java.time.ZoneId.of("America/Denver"))
        }
    }

    @Config(qualifiers = "w392dp-h1000dp-xhdpi")
    @Test fun startAndTestDark() = shot("18-start-and-test-dark.png") {
        Phone(true, "Lab server", null, back = true, actions = {}) {
            ManagerContent(managerUi.copy(busy = setOf(5203)), object : ManagerActions {},
                startTest = local.net.iperf.manager.StartTestProgress(5203, "Waiting for port 5203 to accept connections…"), autoStart = true)
        }
    }

    @Test fun stopOfferDark() = screenShot("18b-stop-instance-offer-dark.png") {
        IperfTheme(dark = true) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {},
                title = { Text("Stop instance?") },
                text = { Text("The test on port 5203 has ended and its reservation was released. Stop the iperf3 instance on Lab server too?") },
                confirmButton = { TextButton({}) { Text("Stop instance") } },
                dismissButton = { TextButton({}) { Text("Keep running") } },
            )
        }
    }

    @Test fun failedPartialDark() = shot("20-failed-partial-dark.png") { homeShot(true, failedPartial) }

    @Test fun failedHistoryDetailDark() = shot("21-history-failed-detail-dark.png") {
        Phone(true, "Result", null, back = true, actions = { IconButton({}) { Icon(Icons.Rounded.DeleteOutline, null) } }) {
            HistoryDetailContent(SampleData.failedLost)
        }
    }

    private val sampleLicences = listOf(
        LicenseText("iPerf for Android 0.2.0", "Apache License 2.0", "Copyright 2026 The project authors.", "Apache License, Version 2.0 … (full text in the app)"),
        LicenseText("iperf3 3.22", "BSD-3-Clause (cJSON: MIT)", "By ESnet / Lawrence Berkeley National Laboratory, built from source with the Android NDK.",
            "Copyright (c) 2014-2026, The Regents of the University of California … (full text in the app)"),
        LicenseText("OpenSSL 3.5.9", "Apache License 2.0", "Linked into iperf3 for server logins (RSA).", "Apache License, Version 2.0 … (full text in the app)"),
        LicenseText("AndroidX, Jetpack Compose, Material 3, Material icons and WindowManager", "Apache License 2.0", "By The Android Open Source Project.", ""),
        LicenseText("Kotlin and kotlinx.coroutines", "Apache License 2.0", "By JetBrains s.r.o. and contributors.", ""),
    )

    @Test fun licencesDark() = shot("19-licences-dark.png") {
        Phone(true, "Open-source licences", null, back = true, actions = {}) {
            local.net.iperf.ui.LicencesContent(sampleLicences)
        }
    }

    @Test fun licenceDetailDark() = shot("19-licence-detail-dark.png") {
        Phone(true, "iperf3 3.22", null, back = true, actions = {}) {
            local.net.iperf.ui.LicenceDetailContent(sampleLicences[1])
        }
    }
}
