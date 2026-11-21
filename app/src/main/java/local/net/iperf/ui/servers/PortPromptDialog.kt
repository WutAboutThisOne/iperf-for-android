package local.net.iperf.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import local.net.iperf.core.validatePort
import local.net.iperf.manager.ManagerClient
import local.net.iperf.ui.PortPromptUi

/**
 * Asked when a saved server is picked: run on its saved port, or on another port just this once.
 * With an iperf3 Manager configured it lists the free ports and can reserve the chosen one first.
 */
@Composable
fun PortPromptDialog(ui: PortPromptUi, onChoose: (port: Int, reserve: Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(portPromptTitle(ui)) },
        text = { PortPromptBody(ui, onChoose) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun portPromptTitle(ui: PortPromptUi) = "Port for ${ui.server.title}"

@Composable
fun PortPromptBody(ui: PortPromptUi, onChoose: (port: Int, reserve: Boolean) -> Unit) {
    val s = ui.server
    var other by remember(s.id) { mutableStateOf("") }
    var reserve by remember(s.id) { mutableStateOf(false) }
    val otherPort = validatePort(other)
    val otherError = other.isNotEmpty() && otherPort == null
    val busy = ui.working
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { onChoose(s.port, reserve) }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("useDefaultPort"),
        ) { Text("Use default (${s.port})") }
        Text(
            "Or use another port for this run only — the saved port stays ${s.port}.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ui.managed) FreePorts(ui, selected = otherPort) { other = it.toString() }
        OutlinedTextField(
            value = other, onValueChange = { v -> other = v.filter(Char::isDigit).take(5) },
            label = { Text("Other port") }, singleLine = true, isError = otherError,
            supportingText = { Text(if (otherError) "Enter 1–65535" else "1–65535") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { otherPort?.let { onChoose(it, reserve) } }),
            modifier = Modifier.fillMaxWidth().testTag("otherPortField"),
        )
        if (ui.managed) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = reserve, onCheckedChange = { reserve = it }, enabled = !busy,
                modifier = Modifier.testTag("reserveCheck"))
            Column {
                Text("Reserve it first (${ManagerClient.DEFAULT_RESERVE_MINUTES} min)", style = MaterialTheme.typography.bodyMedium)
                Text("Released automatically after the test", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(
            onClick = { otherPort?.let { onChoose(it, reserve) } }, enabled = otherPort != null && !busy,
            modifier = Modifier.fillMaxWidth().testTag("useOtherPort"),
        ) { Text(otherPort?.let { "Use port $it this time" } ?: "Use this port this time") }
    }
}

@Composable
private fun FreePorts(ui: PortPromptUi, selected: Int?, onPick: (Int) -> Unit) {
    when {
        ui.loadingPorts -> Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        ui.portsError != null -> Text("Couldn't load free ports: ${ui.portsError}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        ui.ports != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val free = ui.ports.available
            Text(if (free.isEmpty()) "No free ports right now" else "Free ports on the manager (${free.size})",
                style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.heightIn(max = 168.dp).verticalScroll(rememberScrollState()),
            ) {
                free.forEach { p ->
                    FilterChip(selected = selected == p, onClick = { onPick(p) }, label = { Text("$p") },
                        modifier = Modifier.testTag("freePort_$p"))
                }
            }
            if (ui.ports.blocked.isNotEmpty()) Text(
                "Blocked: " + ui.ports.blocked.take(12).joinToString() + if (ui.ports.blocked.size > 12) "…" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
