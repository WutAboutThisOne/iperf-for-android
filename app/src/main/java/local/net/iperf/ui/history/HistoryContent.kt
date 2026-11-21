package local.net.iperf.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.unit.dp
import local.net.iperf.core.formatMbps
import local.net.iperf.data.TestRecord
import local.net.iperf.ui.components.NetworkLine
import local.net.iperf.ui.components.RecordMeta
import local.net.iperf.ui.components.ResultSummary
import local.net.iperf.ui.components.SectionCard
import local.net.iperf.ui.components.ThroughputGraph
import local.net.iperf.ui.components.formatDateTime
import local.net.iperf.ui.theme.Series

interface HistoryActions {
    fun open(record: TestRecord) {}
    fun delete(record: TestRecord) {}
}

@Composable
fun HistoryContent(records: List<TestRecord>, actions: HistoryActions, modifier: Modifier = Modifier, selectedId: String? = null) {
    if (records.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.padding(4.dp))
                Text("No results yet", style = MaterialTheme.typography.titleMedium)
                Text("Finished tests are saved here with the network you were on.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(records, key = { it.id }) { r -> HistoryRow(r, actions, selected = r.id == selectedId) }
    }
}

@Composable
private fun HistoryRow(r: TestRecord, actions: HistoryActions, selected: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().clickable { actions.open(r) }.testTag("historyRow_${r.id}"),
        colors = CardDefaults.cardColors(containerColor =
            if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(r.serverLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    formatDateTime(r.timestamp) + " · " + r.options.protocol.label + " " + r.options.direction.label.lowercase() +
                        (if (r.options.parallel > 1) " ×${r.options.parallel}" else "") +
                        when {
                            r.failed -> " · failed"
                            r.stoppedEarly -> " · stopped"
                            else -> ""
                        },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NetworkLine(r.network)
            }
            Column(horizontalAlignment = Alignment.End) {
                r.summary.upload?.let { Speed(it.mbps, true, Series.up()) }
                r.summary.download?.let { Speed(it.mbps, false, Series.down()) }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Details") }, onClick = { menu = false; actions.open(r) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; actions.delete(r) })
                }
            }
        }
    }
}

@Composable
private fun Speed(mbps: Double?, up: Boolean, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (up) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null, Modifier.size(14.dp), tint = color)
        Spacer(Modifier.width(2.dp))
        Text(formatMbps(mbps), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
fun HistoryDetailContent(r: TestRecord, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (r.failed) SectionCard(container = MaterialTheme.colorScheme.errorContainer, title = r.errorTitle ?: "Failed") {
            Text(r.errorDetail ?: "This test ended with an error. Partial results, if any, are below.",
                color = MaterialTheme.colorScheme.onErrorContainer)
        }
        if (r.summary.upload != null || r.summary.download != null) SectionCard { ResultSummary(r) }
        if (r.points.isNotEmpty()) SectionCard(title = "Throughput") { ThroughputGraph(r.points, r.options.durationSec, omitSec = r.options.omitSec) }
        SectionCard(title = "Details") { RecordMeta(r) }
    }
}

/** Unfolded: the list on the left, the selected test on the right (with its own Delete). */
@Composable
fun HistoryListDetail(records: List<TestRecord>, selected: TestRecord?, actions: HistoryActions, modifier: Modifier = Modifier) {
    local.net.iperf.ui.adaptive.TwoPane(
        modifier = modifier.testTag("historyListDetail"),
        firstFraction = 0.42f,
        first = { HistoryContent(records, actions, selectedId = selected?.id) },
        second = {
            if (selected == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (records.isEmpty()) "Results will appear here" else "Select a test to see its details",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(selected.serverLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatDateTime(selected.timestamp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { actions.delete(selected) }, modifier = Modifier.testTag("detailDelete")) {
                        Icon(Icons.Rounded.DeleteOutline, "Delete result")
                    }
                }
                HorizontalDivider()
                HistoryDetailContent(selected, Modifier.testTag("historyDetailPane"))
            }
        },
    )
}
