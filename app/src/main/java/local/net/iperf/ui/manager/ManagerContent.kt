package local.net.iperf.ui.manager

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Switch
import local.net.iperf.manager.StartTestProgress
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import local.net.iperf.manager.InstanceAction
import local.net.iperf.manager.PortFilter
import local.net.iperf.manager.PortQuery
import local.net.iperf.manager.matchesFilter
import local.net.iperf.manager.noMatchText
import local.net.iperf.manager.searchPorts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import local.net.iperf.manager.InstanceStatus
import local.net.iperf.manager.LogUi
import local.net.iperf.manager.ManagedInstance
import local.net.iperf.manager.ManagerUi

interface ManagerActions {
    fun refresh() {}
    fun act(port: Int, action: InstanceAction) {}
    fun startAll() {}
    fun stopAll() {}
    fun openLogs(port: Int) {}
    fun refreshLogs() {}
    fun setAutoRefresh(on: Boolean) {}
    fun closeLogs() {}
    fun startAndTest(instance: ManagedInstance) {}
    fun openActivity() {}
    fun setAutoStart(on: Boolean) {}
    fun dismissStartTestError() {}
    fun extend(port: Int) {}
    fun releaseAllMine() {}
}

@Composable
fun statusColor(s: InstanceStatus): Color = when (s) {
    InstanceStatus.RUNNING -> Color(0xFF2E9D5B)
    InstanceStatus.STOPPED -> MaterialTheme.colorScheme.outline
    InstanceStatus.RESERVED -> Color(0xFF3B7DD8)
    InstanceStatus.DEGRADED -> Color(0xFFE08A00)
    InstanceStatus.RESTARTING -> Color(0xFF8E6AD8)
    InstanceStatus.BLOCKED -> MaterialTheme.colorScheme.error
    InstanceStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
}

@Composable
fun ManagerContent(
    ui: ManagerUi,
    actions: ManagerActions,
    modifier: Modifier = Modifier,
    startTest: StartTestProgress? = null,
    autoStart: Boolean = true,
    /** Unfolded: port list on the left, the selected port's details and live log on the right. */
    wide: Boolean = false,
    /** Current time, ticking while something is reserved, for the time-left timers. */
    nowMs: Long = System.currentTimeMillis(),
) {
    var confirm by remember { mutableStateOf<Boolean?>(null) } // true = start all, false = stop all
    var confirmReleaseMine by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(PortFilter.ALL) }
    val parsed = PortQuery.parse(query)
    val shown = searchPorts(ui.instances, parsed, filter, ui.ports, ui.mine.keys)
    val search: @Composable () -> Unit = {
        PortSearchBar(query, { query = it }, parsed, filter, { filter = it }, ui,
            inQuery = searchPorts(ui.instances, parsed, PortFilter.ALL, ui.ports, ui.mine.keys))
        if (ui.instances.isNotEmpty() && shown.isEmpty()) Text(noMatchText(ui.instances, parsed, filter),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("noMatch"))
    }
    var selectedPort by rememberSaveable { mutableStateOf<Int?>(null) }
    // Logs opened by selecting a port in the two-pane view are shown inline; close them if the Fold is shut.
    var inlineLogs by rememberSaveable { mutableStateOf(false) }
    // Typing a full port jumps to it: on the unfolded screen it's selected, so its details and log open.
    LaunchedEffect(parsed, wide) {
        if (wide && parsed is PortQuery.Single && ui.instances.any { it.port == parsed.port }) selectedPort = parsed.port
    }
    LaunchedEffect(wide, selectedPort) {
        val p = selectedPort
        if (wide && p != null && ui.logs?.port != p) { actions.openLogs(p); inlineLogs = true }
        if (!wide && inlineLogs) { actions.closeLogs(); inlineLogs = false }
    }
    val header: @Composable () -> Unit = {
        if (ui.loading || ui.bulkBusy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        // Keep Start & test progress/error pinned above the list so it isn't lost when scrolled down.
        startTest?.let { st ->
            StartTestBanner(st, actions, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
    if (!wide) Column(modifier.fillMaxSize()) {
        header()
        search()
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { ManagerSummary(ui, actions, autoStart, onBulk = { confirm = it }, onReleaseMine = { confirmReleaseMine = true }) }
            items(shown, key = { it.port }) { inst ->
                InstanceCard(inst, ui, nowMs, actions, startTestBusy = startTest?.running == true)
            }
        }
    } else local.net.iperf.ui.adaptive.TwoPane(
        modifier = modifier.testTag("managerListDetail"),
        firstFraction = 0.45f,
        first = {
            Column(Modifier.fillMaxSize()) {
                header()
                search()
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item { ManagerSummary(ui, actions, autoStart, onBulk = { confirm = it }, onReleaseMine = { confirmReleaseMine = true }) }
                    items(shown, key = { it.port }) { inst ->
                        InstanceRow(inst, ui, nowMs, selected = inst.port == selectedPort,
                            onClick = { selectedPort = inst.port })
                    }
                }
            }
        },
        second = {
            val sel = ui.instances.firstOrNull { it.port == selectedPort }
            if (sel == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (ui.instances.isEmpty()) "No port selected" else "Select a port to see its details and log",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else PortDetailPane(sel, ui, nowMs, actions, startTestBusy = startTest?.running == true)
        },
    )
    confirm?.let { start ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (start) "Start all instances?" else "Stop all instances?") },
            text = {
                Text(if (start) "Every managed iperf3 instance on ${ui.serverTitle} will be started."
                else "Every managed iperf3 instance on ${ui.serverTitle} will be stopped, including any test someone is running.")
            },
            confirmButton = {
                TextButton(onClick = { confirm = null; if (start) actions.startAll() else actions.stopAll() },
                    modifier = Modifier.testTag("confirmBulk")) { Text(if (start) "Start all" else "Stop all") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
    if (confirmReleaseMine) {
        val ports = ui.mine.keys.sorted()
        AlertDialog(
            onDismissRequest = { confirmReleaseMine = false },
            title = { Text("Release all your reservations?") },
            text = {
                Text("Releases the ${ports.size} port${if (ports.size == 1) "" else "s"} reserved from this phone on ${ui.serverTitle} " +
                    "(${ports.joinToString(", ")}). Instances keep running; anyone can then reserve them. A test already running carries on.")
            },
            confirmButton = {
                TextButton(onClick = { confirmReleaseMine = false; actions.releaseAllMine() }, modifier = Modifier.testTag("confirmReleaseMine")) {
                    Text("Release all")
                }
            },
            dismissButton = { TextButton(onClick = { confirmReleaseMine = false }) { Text("Cancel") } },
        )
    }
    if (!wide) ui.logs?.let { LogDialog(it, actions) }
}

@Composable
private fun PortSearchBar(
    query: String, onQuery: (String) -> Unit, parsed: PortQuery,
    filter: PortFilter, onFilter: (PortFilter) -> Unit, ui: ManagerUi,
    /** Ports matching the typed query (before the chip filter), for the chip counts. */
    inQuery: List<ManagedInstance> = ui.instances,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { v -> onQuery(v.filter { it.isDigit() || it in " -–.to" }.take(13)) },
            modifier = Modifier.fillMaxWidth().testTag("portSearch"),
            singleLine = true,
            placeholder = { Text("Find port: 5250 or 5250-5260") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Clear search") } },
            isError = parsed is PortQuery.Invalid,
            supportingText = (parsed as? PortQuery.Invalid)?.let { { Text(it.message) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Search),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PortFilter.entries.forEach { f ->
                val n = inQuery.count { matchesFilter(it, f, ui.ports, ui.mine.keys) }
                FilterChip(
                    selected = filter == f, onClick = { onFilter(f) },
                    label = { Text(if (ui.instances.isEmpty()) f.label else "${f.label} $n") },
                    modifier = Modifier.testTag("filter_${f.name}"),
                )
            }
        }
    }
}

@Composable
private fun ManagerSummary(ui: ManagerUi, actions: ManagerActions, autoStart: Boolean, onBulk: (Boolean) -> Unit, onReleaseMine: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(ui.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val counts = ui.instances.groupingBy { it.status }.eachCount()
        if (ui.instances.isNotEmpty()) Text(
            "${ui.instances.size} instances · " + InstanceStatus.entries.mapNotNull { s -> counts[s]?.let { "$it ${s.label.lowercase()}" } }.joinToString(" · "),
            style = MaterialTheme.typography.titleSmall,
        )
        ui.ports?.let { p ->
            Text("${p.available.size} free ports" + if (p.blocked.isNotEmpty()) " · ${p.blocked.size} blocked" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onBulk(true) }, enabled = !ui.bulkBusy, modifier = Modifier.testTag("startAll")) {
                Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Start all")
            }
            OutlinedButton(onClick = { onBulk(false) }, enabled = !ui.bulkBusy, modifier = Modifier.testTag("stopAll")) {
                Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(4.dp)); Text("Stop all")
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = actions::refresh, enabled = !ui.loading) { Icon(Icons.Rounded.Refresh, "Refresh") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = autoStart, onCheckedChange = actions::setAutoStart, modifier = Modifier.testTag("autoStartSwitch"))
            Spacer(Modifier.width(8.dp))
            Text("Start test automatically after Start & test", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions::openActivity, modifier = Modifier.testTag("openActivity")) {
                Icon(Icons.Rounded.History, null); Spacer(Modifier.width(6.dp)); Text("Activity log")
            }
            if (ui.mine.isNotEmpty()) OutlinedButton(onClick = onReleaseMine, enabled = !ui.bulkBusy, modifier = Modifier.testTag("releaseAllMine")) {
                Text("Release all mine (${ui.mine.size})")
            }
        }
        ui.error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        if (ui.loaded && ui.error == null && ui.instances.isEmpty()) Text("No managed instances.",
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusLine(i: ManagedInstance, busy: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(statusColor(i.status), CircleShape))
        Spacer(Modifier.width(10.dp))
        Text("Port ${i.port}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(i.status.takeIf { it != InstanceStatus.UNKNOWN }?.label ?: i.rawStatus,
            style = MaterialTheme.typography.labelLarge, color = statusColor(i.status))
        if (busy) { Spacer(Modifier.width(8.dp)); CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
        Spacer(Modifier.width(8.dp))
    }
}

fun isReserved(i: ManagedInstance, ui: ManagerUi): Boolean = local.net.iperf.manager.isReservedPort(i, ui.mine.keys)

/** "Reserved by you · 12:34 left" etc. */
fun reservationLine(i: ManagedInstance, ui: ManagerUi, nowMs: Long): String? {
    if (!isReserved(i, ui)) return null
    val mine = i.port in ui.mine
    val parts = buildList {
        add(if (mine) "Reserved by you" else "Reserved")
        i.reservation?.by?.takeIf { !mine }?.let { add("by $it") }
        local.net.iperf.manager.reservationEndsAt(i, ui.mine[i.port])?.let { add(local.net.iperf.data.formatTimeLeft(it - nowMs)) }
            ?: i.reservation?.minutes?.let { add("$it min") }
        i.reservation?.note?.let { add(it) }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun InstanceMeta(i: ManagedInstance, ui: ManagerUi, nowMs: Long) {
    val meta = listOfNotNull(i.desiredState?.let { "wants $it" }, i.health?.let { "health: $it" })
    if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    reservationLine(i, ui, nowMs)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = statusColor(InstanceStatus.RESERVED), modifier = Modifier.testTag("timeLeft_${i.port}"))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstanceButtons(i: ManagedInstance, ui: ManagerUi, actions: ManagerActions, showLogs: Boolean = true) {
    val busy = i.port in ui.busy
    FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        val running = i.status == InstanceStatus.RUNNING || i.status == InstanceStatus.DEGRADED || i.status == InstanceStatus.RESERVED
        if (!running) TextButton(onClick = { actions.act(i.port, InstanceAction.START) }, enabled = !busy) { Text("Start") }
        if (i.status != InstanceStatus.STOPPED) TextButton(onClick = { actions.act(i.port, InstanceAction.STOP) }, enabled = !busy) { Text("Stop") }
        TextButton(onClick = { actions.act(i.port, InstanceAction.RESTART) }, enabled = !busy) { Text("Restart") }
        if (isReserved(i, ui)) {
            TextButton(onClick = { actions.act(i.port, InstanceAction.RELEASE) }, enabled = !busy, modifier = Modifier.testTag("release_${i.port}")) { Text("Release") }
            if (i.port in ui.mine && ui.extendSupported)
                TextButton(onClick = { actions.extend(i.port) }, enabled = !busy, modifier = Modifier.testTag("extend_${i.port}")) { Text("Extend 15 min") }
        }
        else if (i.status != InstanceStatus.BLOCKED)
            TextButton(onClick = { actions.act(i.port, InstanceAction.RESERVE) }, enabled = !busy) { Text("Reserve") }
        if (showLogs) TextButton(onClick = { actions.openLogs(i.port) }, modifier = Modifier.testTag("logs_${i.port}")) { Text("Logs") }
    }
}

@Composable
private fun StartAndTestButton(i: ManagedInstance, busy: Boolean, actions: ManagerActions, startTestBusy: Boolean) {
    startAndTestLabel(i)?.let { label ->
        FilledTonalButton(onClick = { actions.startAndTest(i) }, enabled = !busy && !startTestBusy,
            modifier = Modifier.padding(bottom = 8.dp).testTag("startTest_${i.port}")) {
            Icon(Icons.Rounded.Speed, null); Spacer(Modifier.width(6.dp)); Text(label)
        }
    }
}

@Composable
private fun InstanceCard(i: ManagedInstance, ui: ManagerUi, nowMs: Long, actions: ManagerActions, startTestBusy: Boolean = false) {
    val busy = i.port in ui.busy
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            StatusLine(i, busy)
            InstanceMeta(i, ui, nowMs)
            InstanceButtons(i, ui, actions)
            StartAndTestButton(i, busy, actions, startTestBusy)
        }
    }
}

/** Two-pane list row: tap to show the port on the right. */
@Composable
private fun InstanceRow(i: ManagedInstance, ui: ManagerUi, nowMs: Long, selected: Boolean, onClick: () -> Unit) {
    val busy = i.port in ui.busy
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor =
            if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().testTag("portRow_${i.port}"),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
            StatusLine(i, busy)
            InstanceMeta(i, ui, nowMs)
        }
    }
}

@Composable
private fun PortDetailPane(i: ManagedInstance, ui: ManagerUi, nowMs: Long, actions: ManagerActions, startTestBusy: Boolean) {
    val busy = i.port in ui.busy
    Column(Modifier.fillMaxSize().padding(16.dp).testTag("portDetail_${i.port}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusLine(i, busy)
        InstanceMeta(i, ui, nowMs)
        InstanceButtons(i, ui, actions, showLogs = false)
        StartAndTestButton(i, busy, actions, startTestBusy)
        val logs = ui.logs?.takeIf { it.port == i.port }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Log", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (logs != null) {
                Checkbox(checked = logs.auto, onCheckedChange = actions::setAutoRefresh)
                Text("Auto-refresh", style = MaterialTheme.typography.bodyMedium)
                logs.total?.let { Spacer(Modifier.width(8.dp)); Text("${logs.lines.size}/$it lines", style = MaterialTheme.typography.labelSmall) }
                IconButton(onClick = actions::refreshLogs) { Icon(Icons.Rounded.Refresh, "Refresh log") }
            }
        }
        if (logs != null) LogLines(logs, Modifier.weight(1f))
    }
}

@Composable
private fun LogLines(logs: LogUi, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(logs.lines.size) { if (logs.lines.isNotEmpty()) list.scrollToItem(logs.lines.lastIndex) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (logs.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        logs.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)) {
            if (logs.lines.isEmpty() && !logs.loading) Text("No log lines", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn(state = list, contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxSize()) {
                items(logs.lines) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
    }
}

/** "Start & test" for a stopped instance, "Reserve & test" for a running one nobody has reserved; null otherwise. */
fun startAndTestLabel(i: ManagedInstance): String? = when {
    i.reservation != null -> null
    i.status == InstanceStatus.STOPPED -> "Start & test"
    i.status == InstanceStatus.RUNNING || i.status == InstanceStatus.DEGRADED -> "Reserve & test"
    else -> null
}

@Composable
fun LogDialog(logs: LogUi, actions: ManagerActions) {
    val list = rememberLazyListState()
    LaunchedEffect(logs.lines.size) { if (logs.lines.isNotEmpty()) list.scrollToItem(logs.lines.lastIndex) }
    AlertDialog(
        onDismissRequest = actions::closeLogs,
        title = { Text("Port ${logs.port} log") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = logs.auto, onCheckedChange = actions::setAutoRefresh)
                    Text("Auto-refresh", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    logs.total?.let { Text("${logs.lines.size}/$it lines", style = MaterialTheme.typography.labelSmall) }
                }
                if (logs.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
                logs.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Box(Modifier.fillMaxWidth().height(360.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)) {
                    if (logs.lines.isEmpty() && !logs.loading) Text("No log lines", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                    LazyColumn(state = list, contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxSize()) {
                        items(logs.lines) { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = actions::refreshLogs) { Text("Refresh") } },
        dismissButton = { TextButton(onClick = actions::closeLogs) { Text("Close") } },
    )
}

@Composable
fun StartTestBanner(st: StartTestProgress, actions: ManagerActions, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor =
        if (st.running) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer),
        modifier = modifier.fillMaxWidth().testTag("startTestCard")) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (st.running) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f)) {
                Text("Start & test · port ${st.port}", style = MaterialTheme.typography.titleSmall)
                Text(st.error ?: st.step, style = MaterialTheme.typography.bodyMedium)
            }
            if (!st.running) TextButton(onClick = actions::dismissStartTestError) { Text("OK") }
        }
    }
}
