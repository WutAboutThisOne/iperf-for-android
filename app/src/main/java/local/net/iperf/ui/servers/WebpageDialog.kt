package local.net.iperf.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import local.net.iperf.data.Server
import local.net.iperf.data.ServerSecrets
import androidx.compose.ui.unit.dp

/** Server webpage + its saved login, with copy buttons (clipboard is cleared again after ~60 s). */
@Composable
fun WebpageDialog(
    server: Server,
    secrets: ServerSecrets,
    onOpen: () -> Unit,
    onCopy: (value: String, sensitive: Boolean, what: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(server.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(server.webUrl, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (secrets.hasWebLogin) {
                    LoginRow("Username", secrets.webUser, null) { onCopy(secrets.webUser, false, "Username") }
                    LoginRow("Password", if (shown) secrets.webPassword else "•".repeat(secrets.webPassword.length.coerceIn(6, 12)),
                        toggle = { shown = !shown } to shown) { onCopy(secrets.webPassword, true, "Password") }
                    Text("Copied values are cleared from the clipboard after about a minute.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("openWebpage")) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Open webpage")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun LoginRow(label: String, value: String, toggle: Pair<() -> Unit, Boolean>?, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.ifEmpty { "—" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (toggle != null) IconButton(onClick = toggle.first) {
            Icon(if (toggle.second) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                if (toggle.second) "Hide password" else "Show password")
        }
        IconButton(onClick = onCopy, modifier = Modifier.testTag("copy$label")) { Icon(Icons.Rounded.ContentCopy, "Copy $label") }
    }
}
