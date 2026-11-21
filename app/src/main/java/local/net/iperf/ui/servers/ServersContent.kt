package local.net.iperf.ui.servers

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import local.net.iperf.core.DEFAULT_PORT
import local.net.iperf.core.normaliseHost
import local.net.iperf.core.validatePort
import local.net.iperf.data.Server
import local.net.iperf.data.ServerSecrets
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Tune

/** Callbacks from the Servers list; default no-op implementations keep previews and tests short. */
interface ServerActions {
    fun pick(server: Server) {}
    fun add() {}
    fun edit(server: Server) {}
    fun delete(server: Server) {}
    fun webpage(server: Server) {}
    fun manage(server: Server) {}
}

/** The Servers tab: saved iperf3 servers, which one is selected, and shortcuts to edit, open or manage each. */
@Composable
fun ServersContent(
    servers: List<Server>, selectedId: String?, actions: ServerActions, modifier: Modifier = Modifier,
    secrets: Map<String, ServerSecrets> = emptyMap(),
) {
    Box(modifier.fillMaxSize()) {
        if (servers.isEmpty()) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Dns, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.padding(4.dp))
                Text("No servers yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Save the iperf3 servers you test against — your NAS, a desktop running iperf3 -s, or a public server.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(servers, key = { it.id }) { s -> ServerRow(s, s.id == selectedId, secrets[s.id] ?: ServerSecrets(), actions) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = actions::add,
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text("Add server") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("addServer"),
        )
    }
}

@Composable
private fun ServerRow(s: Server, selected: Boolean, sec: ServerSecrets, actions: ServerActions) {
    var menu by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().clickable { actions.pick(s) },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.Dns, null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.titleMedium)
                Text(s.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val tags = listOfNotNull(
                    s.webUrl.takeIf { it.isNotBlank() }?.let { "Webpage" + if (sec.hasWebLogin) " + login" else "" },
                    "Manager".takeIf { s.managerBaseUrl != null },
                    "iperf3 login".takeIf { sec.hasIperfAuth },
                )
                if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (s.managerBaseUrl != null) IconButton(onClick = { actions.manage(s) }) {
                Icon(Icons.Rounded.Tune, "Manage ${s.title}")
            }
            if (s.webUrl.isNotBlank()) IconButton(onClick = { actions.webpage(s) }) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open webpage for ${s.title}")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More for ${s.title}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Use for test") }, onClick = { menu = false; actions.pick(s) })
                    if (s.webUrl.isNotBlank()) DropdownMenuItem(
                        text = { Text(if (sec.hasWebLogin) "Webpage & login" else "Open webpage") },
                        onClick = { menu = false; actions.webpage(s) },
                    )
                    if (s.managerBaseUrl != null) DropdownMenuItem(text = { Text("Manage instances") }, onClick = { menu = false; actions.manage(s) })
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; actions.edit(s) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; actions.delete(s) })
                }
            }
        }
    }
}

