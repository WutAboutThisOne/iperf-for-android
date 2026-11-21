package local.net.iperf.ui.test

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import local.net.iperf.core.Direction
import local.net.iperf.core.NetworkSnapshot
import local.net.iperf.core.OptionsDraft
import local.net.iperf.core.Protocol
import local.net.iperf.core.splitMbps
import local.net.iperf.data.Server
import local.net.iperf.run.RunState
import local.net.iperf.ui.components.NetworkLine
import local.net.iperf.ui.components.RecordMeta
import local.net.iperf.ui.components.ResultSummary
import local.net.iperf.ui.components.SectionCard
import local.net.iperf.ui.components.ThroughputGraph
import local.net.iperf.ui.theme.Series
import local.net.iperf.core.OneTimePort

enum class SsidHint { NONE, ASK, DENIED, LOCATION_OFF }

data class TestUi(
    val servers: List<Server> = emptyList(),
    val selected: Server? = null,
    val draft: OptionsDraft = OptionsDraft(),
    val invalid: OptionsDraft.Parsed.Invalid? = null,
    val run: RunState = RunState.Idle,
    val network: NetworkSnapshot? = null,
    val ssidHint: SsidHint = SsidHint.NONE,
    /** One-time port for the selected server (this run only), if chosen in the port prompt. */
    val oneTime: OneTimePort? = null,
    /** When this run's reservation (made from this phone) ends, for the timer. */
    val reservedUntilMs: Long? = null,
    val canExtend: Boolean = false,
    val nowMs: Long = System.currentTimeMillis(),
)

interface TestActions {
    fun select(server: Server) {}
    fun addServer() {}
    fun editDraft(draft: OptionsDraft) {}
    fun start() {}
    fun stop() {}
    fun dismissResult() {}
    fun allowLocation() {}
    fun openLocationSettings() {}
    fun openAppSettings() {}
    fun dismissSsidHint() {}
    /** Open the port prompt for the selected server. */
    fun changePort(server: Server) {}
    fun clearOneTimePort() {}
    fun extendReservation() {}
}

/** Phone: one column. Wide (unfolded): settings left, live graph/result right. Tabletop: graph above the hinge, controls below. */
enum class TestLayout { COMPACT, SIDE_BY_SIDE, TABLETOP }

@Composable
fun TestContent(ui: TestUi, actions: TestActions, modifier: Modifier = Modifier, layout: TestLayout = TestLayout.COMPACT) {
    val running = ui.run is RunState.Running
    val focus = LocalFocusManager.current
    val scroll = rememberScrollState()
    val outScroll = rememberScrollState()
    // If the user started from a scrolled "Run again" position, jump back so the
    // error banner or result numbers aren't hidden under the top bar.
    LaunchedEffect(ui.run) {
        if (ui.run is RunState.Done || ui.run is RunState.Failed) { scroll.animateScrollTo(0); outScroll.animateScrollTo(0) }
    }
    when (layout) {
        TestLayout.COMPACT -> Column(
            modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ServerPicker(ui, actions, enabled = !running)
            RunOutput(ui, actions, graphHeight = 180.dp, inlineStop = true)
            if (ui.ssidHint != SsidHint.NONE && !running) SsidHintCard(ui.ssidHint, actions)
            OptionsCard(ui, actions, enabled = !running)
            if (!running) StartStop(ui, actions, onBeforeStart = { focus.clearFocus() })
            Spacer(Modifier.height(8.dp))
        }
        TestLayout.SIDE_BY_SIDE -> local.net.iperf.ui.adaptive.TwoPane(
            modifier = modifier.testTag("testSideBySide"),
            firstFraction = 0.42f,
            firstMinWidth = 380.dp,
            first = {
                Column(
                    Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ServerPicker(ui, actions, enabled = !running)
                    if (ui.ssidHint != SsidHint.NONE && !running) SsidHintCard(ui.ssidHint, actions)
                    OptionsCard(ui, actions, enabled = !running)
                    Spacer(Modifier.height(8.dp))
                }
            },
            second = {
                // Start / Run again / Stop sit on top of the right pane and the graph fills the rest of the height,
                // so neither is ever scrolled off.
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    val g = (maxHeight - 290.dp).coerceIn(180.dp, 460.dp)
                    Column(
                        Modifier.fillMaxSize().verticalScroll(outScroll).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StartStop(ui, actions, onBeforeStart = { focus.clearFocus() })
                        if (ui.run == RunState.Idle) IdleGraphCard(ui, g)
                        RunOutput(ui, actions, graphHeight = g, inlineStop = false)
                    }
                }
            },
        )
        TestLayout.TABLETOP -> local.net.iperf.ui.adaptive.TopBottomPane(
            modifier = modifier.testTag("testTabletop"),
            top = {
                // The graph fills what's above the hinge (less the live card's header and legend).
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    val g = (maxHeight - if (ui.run is RunState.Running) 140.dp else 150.dp).coerceAtLeast(96.dp)
                    Column(
                        Modifier.fillMaxSize().verticalScroll(outScroll).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (ui.run == RunState.Idle) IdleGraphCard(ui, g)
                        RunOutput(ui, actions, graphHeight = g, inlineStop = false, dense = true)
                    }
                }
            },
            bottom = {
                Column(
                    Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StartStop(ui, actions, onBeforeStart = { focus.clearFocus() })
                    ServerPicker(ui, actions, enabled = !running)
                    if (ui.ssidHint != SsidHint.NONE && !running) SsidHintCard(ui.ssidHint, actions)
                    OptionsCard(ui, actions, enabled = !running)
                    Spacer(Modifier.height(8.dp))
                }
            },
        )
    }
}

/** Live readout, result or error. [inlineStop]: the phone layout keeps Stop right under the live card. */
@Composable
private fun RunOutput(ui: TestUi, actions: TestActions, graphHeight: androidx.compose.ui.unit.Dp, inlineStop: Boolean, dense: Boolean = false) {
    when (val r = ui.run) {
        is RunState.Running -> {
            LiveCard(r, graphHeight, dense)
            if (inlineStop) StartStop(ui, actions, onBeforeStart = {})
        }
        is RunState.Done -> SectionCard(title = if (r.record.stoppedEarly) "Result (stopped)" else "Result") {
            ResultSummary(r.record)
            ThroughputGraph(r.record.points, r.record.options.durationSec, omitSec = r.record.options.omitSec, height = graphHeight)
            RecordMeta(r.record)
            Text("Saved to History", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        is RunState.Failed -> {
            ErrorCard(r, actions)
            if (r.record.summary.upload != null || r.record.summary.download != null || r.record.points.isNotEmpty()) {
                SectionCard(title = "Partial result") {
                    if (r.record.summary.upload != null || r.record.summary.download != null) ResultSummary(r.record)
                    if (r.record.points.isNotEmpty()) ThroughputGraph(r.record.points, r.record.options.durationSec,
                        omitSec = r.record.options.omitSec, height = graphHeight)
                    RecordMeta(r.record)
                }
            }
        }
        RunState.Idle -> Unit
    }
}

@Composable
private fun IdleGraphCard(ui: TestUi, graphHeight: androidx.compose.ui.unit.Dp) {
    SectionCard(title = "Live graph") {
        Text(
            ui.selected?.let { "Press Start test to measure ${it.title}. The live rate is drawn here." } ?: "Choose a server, then press Start test.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ThroughputGraph(emptyList(), ui.draft.duration.trim().toIntOrNull()?.takeIf { it > 0 } ?: 10,
            height = graphHeight)
    }
}

@Composable
private fun ServerPicker(ui: TestUi, actions: TestActions, enabled: Boolean) {
    SectionCard {
        if (ui.servers.isEmpty()) {
            Text("No saved servers yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add the address of a machine running iperf3 -s (default port 5201).",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = actions::addServer) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Add server")
            }
        } else {
            var open by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }) {
                OutlinedTextField(
                    value = ui.selected?.let { sel -> "${sel.title}  ·  ${ui.oneTime?.let { sel.copy(port = it.port).address } ?: sel.address}" } ?: "Choose a server",
                    onValueChange = {},
                    readOnly = true,
                    enabled = enabled,
                    label = { Text("Server") },
                    isError = ui.invalid?.field == OptionsDraft.Field.SERVER,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                    modifier = Modifier.fillMaxWidth().menuAnchor().testTag("serverPicker"),
                    singleLine = true,
                )
                ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    ui.servers.forEach { s ->
                        DropdownMenuItem(
                            text = { Column { Text(s.title); Text(s.address, style = MaterialTheme.typography.bodySmall) } },
                            onClick = { open = false; actions.select(s) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Add server…") },
                        leadingIcon = { Icon(Icons.Rounded.Add, null) },
                        onClick = { open = false; actions.addServer() },
                    )
                }
            }
            ui.selected?.let { sel -> PortLine(sel, ui, actions, enabled) }
        }
        ui.network?.let { NetworkLine(it) }
    }
}

@Composable
private fun PortLine(sel: Server, ui: TestUi, actions: TestActions, enabled: Boolean) {
    val oneTime = ui.oneTime
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                if (oneTime != null) {
                    Text(
                        "Port ${oneTime.port} for this run" + if (oneTime.reserved) " · reserved" else "",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    )
                    ui.reservedUntilMs?.takeIf { oneTime.reserved }?.let { until ->
                        Text(local.net.iperf.data.formatTimeLeft(until - ui.nowMs), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("oneTimeTimeLeft"))
                    }
                    Text("Saved port ${sel.port} is unchanged", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Port ${sel.port} (saved)", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = { actions.changePort(sel) }, enabled = enabled, modifier = Modifier.testTag("changePort")) { Text("Change port") }
        }
        if (oneTime != null) Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            // Extend stays available during a test (a reservation can run out before an endless test ends).
            if (oneTime.reserved && ui.canExtend) TextButton(onClick = actions::extendReservation, modifier = Modifier.testTag("extendOneTime")) {
                Text("Extend 15 min")
            }
            TextButton(onClick = actions::clearOneTimePort, enabled = enabled, modifier = Modifier.testTag("releaseOneTime")) {
                Text(if (oneTime.reserved) "Release" else "Use saved")
            }
        }
    }
}

@Composable
private fun OptionsCard(ui: TestUi, actions: TestActions, enabled: Boolean) {
    val d = ui.draft
    SectionCard(title = "Test options") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Protocol.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = d.protocol == p, enabled = enabled,
                    onClick = { actions.editDraft(d.copy(protocol = p)) },
                    shape = SegmentedButtonDefaults.itemShape(i, Protocol.entries.size),
                ) { Text(p.label) }
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Direction.entries.forEachIndexed { i, dir ->
                SegmentedButton(
                    selected = d.direction == dir, enabled = enabled,
                    onClick = { actions.editDraft(d.copy(direction = dir)) },
                    shape = SegmentedButtonDefaults.itemShape(i, Direction.entries.size),
                ) { Text(dir.label) }
            }
        }
        Text(
            when (d.direction) {
                Direction.UPLOAD -> "Phone sends to the server."
                Direction.DOWNLOAD -> "Server sends to the phone (-R)."
                Direction.BIDIR -> "Both directions at once (--bidir, iperf3 3.7+ server)."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val err = ui.invalid?.field
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField("Duration", d.duration, "s", enabled, err == OptionsDraft.Field.DURATION, Modifier.weight(1f)) { actions.editDraft(d.copy(duration = it)) }
            NumberField("Streams", d.parallel, "-P", enabled, err == OptionsDraft.Field.PARALLEL, Modifier.weight(1f)) { actions.editDraft(d.copy(parallel = it)) }
        }
        Text(
            if (d.duration.trim() == "0") "Duration 0 = endless: the test runs until you press Stop."
            else "Tip: a duration of 0 runs an endless test until you press Stop.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("durationHint"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField("Omit first", d.omit, "s", enabled, err == OptionsDraft.Field.OMIT, Modifier.weight(1f)) { actions.editDraft(d.copy(omit = it)) }
            if (d.protocol == Protocol.UDP) {
                NumberField("UDP target", d.udpBandwidth, "Mbps", enabled, err == OptionsDraft.Field.BANDWIDTH, Modifier.weight(1f), decimal = true) { actions.editDraft(d.copy(udpBandwidth = it)) }
            } else Spacer(Modifier.weight(1f))
        }
        if (d.protocol == Protocol.UDP) Text(
            "UDP sends at the target rate; 0 = unlimited. Loss and jitter are measured by the receiver.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ui.invalid?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun NumberField(
    label: String, value: String, suffix: String, enabled: Boolean, error: Boolean, modifier: Modifier,
    decimal: Boolean = false, onChange: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || (decimal && (it == '.' || it == ',')) }.take(7)) },
        label = { Text(label) },
        suffix = { Text(suffix) },
        enabled = enabled,
        isError = error,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = modifier,
    )
}

@Composable
private fun StartStop(ui: TestUi, actions: TestActions, onBeforeStart: () -> Unit) {
    val r = ui.run
    if (r is RunState.Running) {
        Button(
            onClick = actions::stop, enabled = !r.stopping,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("stop"),
        ) {
            Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(8.dp)); Text(if (r.stopping) "Stopping…" else "Stop")
        }
    } else {
        Button(
            onClick = { onBeforeStart(); actions.start() }, enabled = ui.selected != null,
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("start"),
        ) {
            Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp))
            Text(if (r is RunState.Done || r is RunState.Failed) "Run again" else "Start test")
        }
    }
}

@Composable
private fun LiveCard(r: RunState.Running, graphHeight: androidx.compose.ui.unit.Dp = 180.dp, dense: Boolean = false) {
    if (dense) return DenseLiveCard(r, graphHeight)
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.serverLabel, style = MaterialTheme.typography.titleMedium)
                Text(r.options.describe(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                when {
                    r.stopping -> "Stopping"
                    !r.connected -> "Connecting…"
                    r.endless -> "${formatElapsed(r.elapsedSec)} · endless"
                    else -> "${r.elapsedSec.toInt()} / ${r.options.durationSec + r.options.omitSec} s"
                },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("liveClock"),
            )
        }
        if (r.endless && r.connected && !r.stopping) LinearProgressIndicator(Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { r.progress }, modifier = Modifier.fillMaxWidth())
        val last = r.latest
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (r.options.direction != Direction.DOWNLOAD) LiveNumber(last?.upMbps, true, Series.up())
            if (r.options.direction != Direction.UPLOAD) LiveNumber(last?.downMbps, false, Series.down())
        }
        ThroughputGraph(r.points, r.options.durationSec, omitSec = r.options.omitSec,
            windowSec = if (r.endless) ENDLESS_WINDOW_SEC else null, height = graphHeight)
        if (r.endless) Text(
            "Runs until you press Stop. The graph shows the last ${ENDLESS_WINDOW_SEC / 60} minutes; the saved result covers the whole run.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (last?.omitted == true) Text(
            "Warm-up (omitted from the result)",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LiveNumber(mbps: Double?, up: Boolean, color: Color) {
    val (num, unit) = splitMbps(mbps)
    Row(verticalAlignment = Alignment.Bottom) {
        Icon(if (up) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, if (up) "Upload" else "Download",
            Modifier.size(22.dp).padding(bottom = 6.dp), tint = color)
        Text(if (mbps == null) "—" else num, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        if (mbps != null) Text(unit, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
    }
}

@Composable
private fun ErrorCard(r: RunState.Failed, actions: TestActions) {
    SectionCard(container = MaterialTheme.colorScheme.errorContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Text(r.error.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        Text(r.error.detail, color = MaterialTheme.colorScheme.onErrorContainer)
        r.error.raw?.takeIf { it != r.error.detail }?.let {
            Text("iperf3: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f))
        }
        Text("Saved to History", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = actions::dismissResult) { Text("Dismiss") }
    }
}

@Composable
private fun SsidHintCard(hint: SsidHint, actions: TestActions) {
    SectionCard(container = MaterialTheme.colorScheme.secondaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LocationOn, null)
            Spacer(Modifier.width(8.dp))
            Text("Record the Wi-Fi name?", style = MaterialTheme.typography.titleSmall)
        }
        Text(
            when (hint) {
                SsidHint.ASK -> "Android only reveals the Wi-Fi network name (SSID) to apps with location access. It's used only to label your results and never leaves the phone. Band, signal and link speed are recorded either way."
                SsidHint.DENIED -> "Location access was declined, so results are saved without the Wi-Fi name. You can allow it later in app settings."
                SsidHint.LOCATION_OFF -> "Location is turned off, so Android hides the Wi-Fi name. Turn it on to label results with the SSID."
                SsidHint.NONE -> ""
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (hint) {
                SsidHint.ASK -> Button(onClick = actions::allowLocation) { Text("Allow") }
                SsidHint.DENIED -> OutlinedButton(onClick = actions::openAppSettings) { Text("App settings") }
                SsidHint.LOCATION_OFF -> OutlinedButton(onClick = actions::openLocationSettings) { Text("Location settings") }
                SsidHint.NONE -> Unit
            }
            TextButton(onClick = actions::dismissSsidHint) { Text("Not now") }
        }
    }
}

const val ENDLESS_WINDOW_SEC = 300

/** 75 → "1:15", 3725 → "1:02:05". */
fun formatElapsed(sec: Double): String {
    val t = sec.toLong().coerceAtLeast(0)
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Tabletop: one header row (server, rates, clock) so the graph gets most of the half screen above the hinge. */
@Composable
private fun DenseLiveCard(r: RunState.Running, graphHeight: androidx.compose.ui.unit.Dp) {
    SectionCard {
        val last = r.latest
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) {
                Text(r.serverLabel, style = MaterialTheme.typography.titleMedium)
                Text(r.options.describe(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (r.options.direction != Direction.DOWNLOAD) LiveNumber(last?.upMbps, true, Series.up())
            if (r.options.direction != Direction.UPLOAD) LiveNumber(last?.downMbps, false, Series.down())
            Text(
                when {
                    r.stopping -> "Stopping"
                    !r.connected -> "Connecting…"
                    r.endless -> "${formatElapsed(r.elapsedSec)} · endless"
                    else -> "${r.elapsedSec.toInt()} / ${r.options.durationSec + r.options.omitSec} s"
                },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("liveClock"),
            )
        }
        if (r.endless && r.connected && !r.stopping) LinearProgressIndicator(Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { r.progress }, modifier = Modifier.fillMaxWidth())
        ThroughputGraph(r.points, r.options.durationSec, omitSec = r.options.omitSec,
            windowSec = if (r.endless) ENDLESS_WINDOW_SEC else null, height = graphHeight)
    }
}
