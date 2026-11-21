package local.net.iperf.ui.manager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.IosShare
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import local.net.iperf.data.ActivityEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss")

/**
 * Local, per-server log of every Manager action this phone performed (start, stop, reserve, release,
 * Start & test steps) with time, ports and outcome. Stored only on the device; logins are never recorded.
 */
@Composable
fun ActivityLogContent(serverTitle: String, entries: List<ActivityEntry>, modifier: Modifier = Modifier, zone: ZoneId = ZoneId.systemDefault()) {
    LazyColumn(
        modifier.fillMaxSize().testTag("activityLog"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Manager actions taken from this phone on $serverTitle, newest first (up to 2,000). Logins are never recorded.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entries.isEmpty()) item {
            Text("No activity yet. Start, stop, reserve and Start & test actions will appear here.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
        }
        items(entries, key = { it.id }) { e -> EntryCard(e, zone) }
    }
}

@Composable
private fun EntryCard(e: ActivityEntry, zone: ZoneId) {
    val good = Color(0xFF2E9D5B)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(if (e.ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, if (e.ok) "OK" else "Failed",
                tint = if (e.ok) good else MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 10.dp, top = 2.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.actionLabel, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (e.ports.isNotEmpty()) Text(
                        if (e.ports.size <= 3) "Port " + e.ports.joinToString(", ") else "${e.ports.size} ports",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(timeFmt.format(Instant.ofEpochMilli(e.timeMs).atZone(zone)), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                e.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = if (e.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun ActivityLogMenu(enabled: Boolean, onExport: (csv: Boolean) -> Unit, onClear: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag("activityMenu")) { Icon(Icons.Rounded.MoreVert, "Activity log options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Export JSON") }, leadingIcon = { Icon(Icons.Rounded.IosShare, null) },
                onClick = { open = false; onExport(false) })
            DropdownMenuItem(text = { Text("Export CSV") }, leadingIcon = { Icon(Icons.Rounded.IosShare, null) },
                onClick = { open = false; onExport(true) })
            DropdownMenuItem(text = { Text("Clear log", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { open = false; onClear() }, modifier = Modifier.testTag("clearActivity"))
        }
    }
}
