package local.net.iperf.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import local.net.iperf.core.FlowSummary
import local.net.iperf.core.IntervalPoint
import local.net.iperf.core.enriched
import local.net.iperf.core.Protocol
import local.net.iperf.core.formatBytes
import local.net.iperf.core.formatMbps
import local.net.iperf.core.formatMs
import local.net.iperf.core.formatPercent
import local.net.iperf.core.splitMbps
import local.net.iperf.data.TestRecord
import local.net.iperf.ui.theme.Series

/** Big number + detail rows for each direction of a finished test. */
@Composable
fun ResultSummary(record: TestRecord, modifier: Modifier = Modifier) {
    val udp = record.options.protocol == Protocol.UDP
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            record.summary.upload?.let { FlowHeadline("Upload", it, Series.up(), up = true, Modifier.weight(1f)) }
            record.summary.download?.let { FlowHeadline("Download", it, Series.down(), up = false, Modifier.weight(1f)) }
        }
        record.summary.upload?.let { FlowDetails("Upload", it, udp, record.points, both = record.summary.download != null) }
        record.summary.download?.let { FlowDetails("Download", it, udp, record.points, both = record.summary.upload != null) }
    }
}

@Composable
private fun FlowHeadline(label: String, f: FlowSummary, color: Color, up: Boolean, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (up) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null, Modifier.size(16.dp), tint = color)
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = color)
            }
            val (num, unit) = splitMbps(f.mbps)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(num, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

@Composable
private fun FlowDetails(label: String, f: FlowSummary, udp: Boolean, points: List<IntervalPoint>, both: Boolean) {
    val stats = f.enriched(points, up = label == "Upload")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (both) Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        KeyValue("Average", formatMbps(stats.mbps))
        KeyValue("Peak", formatMbps(stats.peakMbps))
        KeyValue("Lowest", formatMbps(stats.minMbps))
        KeyValue("Sent", formatBytes(stats.bytesSent))
        KeyValue("Received", formatBytes(stats.bytesReceived))
        if (udp) {
            KeyValue("Jitter", formatMs(stats.jitterMs))
            val lost = if (stats.lostPackets != null && stats.packets != null) "  (${stats.lostPackets}/${stats.packets})" else ""
            KeyValue("Packet loss", formatPercent(stats.lostPercent) + lost)
        } else {
            KeyValue("Retransmits", stats.retransmits?.toString() ?: "—")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

@Composable
fun RecordMeta(record: TestRecord, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${record.serverLabel} · ${record.options.host}:${record.options.port}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(record.options.describe(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        NetworkLine(record.network)
        Text(
            formatDateTime(record.timestamp) + (record.iperfVersion?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            record.failed -> AssistChip(onClick = {}, label = { Text(record.errorTitle ?: "Failed") })
            record.stoppedEarly -> AssistChip(onClick = {}, label = { Text("Stopped early — partial result") })
        }
    }
}
