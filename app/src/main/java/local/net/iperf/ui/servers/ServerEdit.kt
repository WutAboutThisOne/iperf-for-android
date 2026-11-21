package local.net.iperf.ui.servers

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import local.net.iperf.core.DEFAULT_PORT
import local.net.iperf.core.PublicKeyPem
import local.net.iperf.core.normaliseHost
import local.net.iperf.core.normaliseWebUrl
import local.net.iperf.core.validatePort
import local.net.iperf.data.Server
import local.net.iperf.data.ServerSecrets
import local.net.iperf.manager.ManagerClient
import local.net.iperf.manager.ManagerException

/** Editable form for a server and its (encrypted-at-rest) logins. Pure state so it can be tested without UI. */
data class ServerForm(
    val host: String = "",
    val port: String = DEFAULT_PORT.toString(),
    val label: String = "",
    val webUrl: String = "",
    val webUser: String = "",
    val webPassword: String = "",
    val managerEnabled: Boolean = false,
    val managerUrl: String = "",
    val separateApiLogin: Boolean = false,
    val apiUser: String = "",
    val apiPassword: String = "",
    val iperfUser: String = "",
    val iperfPassword: String = "",
    val iperfKey: String = "",
) {
    data class Errors(
        val host: Boolean = false, val port: Boolean = false, val webUrl: Boolean = false,
        val managerUrl: String? = null, val iperf: String? = null,
    ) { val any get() = host || port || webUrl || managerUrl != null || iperf != null }

    fun validate(): Errors {
        val web = normaliseWebUrl(webUrl)
        val mgrError = if (!managerEnabled) null else {
            val base = managerUrl.trim().ifEmpty { web ?: "" }
            when {
                base.isEmpty() -> "Enter the manager URL (or a webpage URL to reuse)"
                else -> runCatching { ManagerClient.validateBase(normaliseWebUrl(base) ?: base); null }
                    .getOrElse { (it as? ManagerException)?.message ?: "Not a valid URL" }
            }
        }
        val anyIperf = iperfUser.isNotEmpty() || iperfPassword.isNotEmpty() || iperfKey.isNotBlank()
        val iperfError = when {
            !anyIperf -> null
            iperfUser.isEmpty() || iperfPassword.isEmpty() || iperfKey.isBlank() ->
                "iperf3 login needs a username, password and the server's public key (or clear all three)"
            else -> (PublicKeyPem.check(iperfKey) as? PublicKeyPem.Check.Invalid)?.message
        }
        return Errors(normaliseHost(host) == null, validatePort(port) == null, web == null, mgrError, iperfError)
    }

    /** Null if invalid. */
    fun toServer(base: Server): Pair<Server, ServerSecrets>? {
        if (validate().any) return null
        val key = (PublicKeyPem.check(iperfKey) as? PublicKeyPem.Check.Ok)?.pem ?: ""
        val server = base.copy(
            host = normaliseHost(host)!!, port = validatePort(port)!!, label = label.trim(),
            webUrl = normaliseWebUrl(webUrl)!!, managerEnabled = managerEnabled,
            managerUrl = if (managerUrl.isBlank()) "" else normaliseWebUrl(managerUrl) ?: managerUrl.trim(),
        )
        val secrets = ServerSecrets(
            webUser = webUser.trim(), webPassword = webPassword,
            iperfUser = iperfUser.trim(), iperfPassword = iperfPassword, iperfPublicKey = key,
            apiUser = if (separateApiLogin) apiUser.trim() else "", apiPassword = if (separateApiLogin) apiPassword else "",
        )
        return server to secrets
    }

    companion object {
        fun from(s: Server, sec: ServerSecrets) = ServerForm(
            host = s.host, port = s.port.toString(), label = s.label, webUrl = s.webUrl,
            webUser = sec.webUser, webPassword = sec.webPassword,
            managerEnabled = s.managerEnabled, managerUrl = s.managerUrl,
            separateApiLogin = sec.hasSeparateApiLogin, apiUser = sec.apiUser, apiPassword = sec.apiPassword,
            iperfUser = sec.iperfUser, iperfPassword = sec.iperfPassword, iperfKey = sec.iperfPublicKey,
        )
    }
}

/** Full-screen add/edit dialog. [initial] with a blank id means "add". */
@Composable
fun ServerDialog(
    initial: Server,
    initialSecrets: ServerSecrets = ServerSecrets(),
    onSave: (Server, ServerSecrets) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var form by remember { mutableStateOf(ServerForm.from(initial, initialSecrets)) }
        var tried by remember { mutableStateOf(false) }
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (initial.id.isBlank()) "Add server" else "Edit server") },
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Cancel") } },
                        actions = {
                            TextButton(onClick = {
                                tried = true
                                form.toServer(initial)?.let { (s, sec) -> onSave(s, sec) }
                            }) { Text("Save") }
                        },
                    )
                },
            ) { pad ->
                ServerEditContent(form, { form = it }, tried, Modifier.padding(pad).imePadding())
            }
        }
    }
}

@Composable
fun ServerEditContent(form: ServerForm, onChange: (ServerForm) -> Unit, tried: Boolean, modifier: Modifier = Modifier) {
    val err = if (tried) form.validate() else ServerForm.Errors()
    val context = LocalContext.current
    var importError by remember { mutableStateOf<String?>(null) }
    val importKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(64 * 1024) }?.toString(Charsets.UTF_8)
        }.getOrNull()
        if (text == null) importError = "Couldn't read that file" else when (val c = PublicKeyPem.check(text)) {
            is PublicKeyPem.Check.Ok -> { importError = null; onChange(form.copy(iperfKey = c.pem)) }
            is PublicKeyPem.Check.Invalid -> importError = c.message
        }
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = form.host, onValueChange = { onChange(form.copy(host = it.trim())) }, label = { Text("Host or IP") },
            placeholder = { Text("192.168.1.10") }, singleLine = true, isError = err.host,
            supportingText = { if (err.host) Text("Enter a host name or IP address") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("hostField"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.port, onValueChange = { v -> onChange(form.copy(port = v.filter(Char::isDigit).take(5))) },
                label = { Text("Port") }, singleLine = true, isError = err.port,
                supportingText = { if (err.port) Text("1–65535") else Text("Default $DEFAULT_PORT") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.weight(0.4f),
            )
            OutlinedTextField(
                value = form.label, onValueChange = { onChange(form.copy(label = it.take(40))) }, label = { Text("Label (optional)") },
                placeholder = { Text("Home NAS") }, singleLine = true, modifier = Modifier.weight(0.6f),
            )
        }
        OutlinedTextField(
            value = form.webUrl, onValueChange = { onChange(form.copy(webUrl = it.trim())) }, label = { Text("Server webpage (optional)") },
            placeholder = { Text("https://iperf.example.net") }, singleLine = true, isError = err.webUrl,
            supportingText = { Text(if (err.webUrl) "Not a web address" else "Opens in your browser; https:// is added if you leave it out") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("webUrlField"),
        )

        Section("Webpage login (optional)", "Saved encrypted on this phone. Copy it from the server's menu to paste into the site.")
        LoginFields(form.webUser, form.webPassword, "web",
            { onChange(form.copy(webUser = it)) }, { onChange(form.copy(webPassword = it)) })

        Section("iperf3 Manager API (optional)", "For servers running iperf3 Manager: see instances, start/stop them, and pick or reserve free ports.")
        SwitchRow("Use the manager API", form.managerEnabled, "managerSwitch") { onChange(form.copy(managerEnabled = it)) }
        if (form.managerEnabled) {
            OutlinedTextField(
                value = form.managerUrl, onValueChange = { onChange(form.copy(managerUrl = it.trim())) },
                label = { Text("Manager API URL") },
                placeholder = { Text(normaliseWebUrl(form.webUrl)?.ifEmpty { null } ?: "https://iperf.example.net") },
                singleLine = true, isError = err.managerUrl != null,
                supportingText = { Text(err.managerUrl ?: "Leave blank to use the webpage URL. https only.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("managerUrlField"),
            )
            SwitchRow("Separate API login", form.separateApiLogin, "apiLoginSwitch") { onChange(form.copy(separateApiLogin = it)) }
            if (form.separateApiLogin) LoginFields(form.apiUser, form.apiPassword, "api",
                { onChange(form.copy(apiUser = it)) }, { onChange(form.copy(apiPassword = it)) })
            else Text("Uses the webpage login above (HTTP Basic auth).", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("iperf3 server login (optional)", "Only for servers started with --rsa-private-key-path and --authorized-users-path.")
        LoginFields(form.iperfUser, form.iperfPassword, "iperf",
            { onChange(form.copy(iperfUser = it)) }, { onChange(form.copy(iperfPassword = it)) })
        OutlinedTextField(
            value = form.iperfKey, onValueChange = { onChange(form.copy(iperfKey = it)) },
            label = { Text("Server RSA public key (PEM)") },
            placeholder = { Text("-----BEGIN PUBLIC KEY-----\n…\n-----END PUBLIC KEY-----") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            minLines = 3, maxLines = 6, isError = err.iperf != null,
            supportingText = { (err.iperf ?: importError)?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth().testTag("iperfKeyField"),
        )
        OutlinedButton(onClick = { importKey.launch(arrayOf("*/*")) }) {
            Icon(Icons.Rounded.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Import key file")
        }
        Spacer(Modifier.padding(12.dp))
    }
}

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0
    while (total < max) { val n = read(buf, 0, minOf(buf.size, max - total)); if (n < 0) break; out.write(buf, 0, n); total += n }
    return out.toByteArray()
}

@Composable
private fun Section(title: String, hint: String) {
    Spacer(Modifier.padding(2.dp))
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun LoginFields(user: String, password: String, tag: String, onUser: (String) -> Unit, onPassword: (String) -> Unit) {
    OutlinedTextField(
        value = user, onValueChange = onUser, label = { Text("Username") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrect = false),
        modifier = Modifier.fillMaxWidth().testTag("${tag}User"),
    )
    PasswordField(password, onPassword, Modifier.fillMaxWidth().testTag("${tag}Password"))
}

@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, label: String = "Password") {
    var shown by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next, autoCorrect = false),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (shown) "Hide password" else "Show password")
            }
        },
        modifier = modifier,
    )
}
