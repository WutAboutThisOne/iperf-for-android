package local.net.iperf.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import local.net.iperf.core.ChangelogEntry
import local.net.iperf.ui.components.SectionCard

/**
 * One component on the Licences page.
 *
 * @param title component name with its version, e.g. "iperf3 3.22"
 * @param licence short licence name shown in the list, e.g. "BSD-3-Clause"
 * @param intro one-line description shown at the top of the detail page
 * @param text full licence text (may be blank)
 * @param notice NOTICE text the licence asks to be reproduced (may be blank)
 */
data class LicenseText(val title: String, val licence: String, val intro: String, val text: String, val notice: String = "")

/** The Open-source licences page, linked from About: a compact list; tapping a row opens [LicenceDetailContent]. */
@Composable
fun LicencesContent(licenses: List<LicenseText>, modifier: Modifier = Modifier, onOpen: (Int) -> Unit = {}) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp).testTag("licencesPage"),
    ) {
        Text("Tap a component to read its full licence.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        licenses.forEachIndexed { i, l ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Row(
                Modifier.fillMaxWidth().clickable(onClickLabel = "Read licence") { onOpen(i) }
                    .padding(horizontal = 16.dp, vertical = 12.dp).testTag("licence_$i"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(l.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(l.licence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Full licence page for one component: intro, any NOTICE text, then the licence text. */
@Composable
fun LicenceDetailContent(licence: LicenseText, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("licenceDetail"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(licence.licence, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (licence.intro.isNotBlank()) Text(licence.intro, style = MaterialTheme.typography.bodyMedium)
        val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        if (licence.notice.isNotBlank()) Text(licence.notice, style = mono, modifier = Modifier.testTag("licenceNotice"))
        if (licence.text.isNotBlank()) Text(licence.text, style = mono, modifier = Modifier.testTag("licenceText"))
    }
}

/** About page: version, privacy/permissions summary, collapsible changelog and a link to the licences page. */
@Composable
fun AboutContent(
    version: String,
    versionCode: Int,
    changelog: List<ChangelogEntry>,
    modifier: Modifier = Modifier,
    onLicences: () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "iPerf for Android") {
            Text("Version $version (build $versionCode)", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("aboutVersion"))
            Text(
                "An iperf3 client. No ads, no analytics, no accounts — results stay on this phone unless you export them. " +
                    "Network access is used only to talk to the iperf3 servers (and iperf3 Manager APIs) you add. " +
                    "Saved logins are encrypted with a key kept in the Android Keystore and are never exported or backed up.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Permissions: Internet (run tests), network & Wi-Fi state (label results), a foreground service, wake lock " +
                    "and notification while a test runs (so it keeps going in the background and can be stopped from the " +
                    "notification). Location is optional and only asked for to read the Wi-Fi name, which Android hides otherwise.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard(title = "What's new") { ChangelogList(changelog, installed = version) }
        SectionCard(title = "Open-source licences") {
            Text("iPerf for Android is open source under the Apache License 2.0. It includes iperf3 (BSD-3-Clause, " +
                "with cJSON under MIT) and OpenSSL (Apache 2.0).", style = MaterialTheme.typography.bodyMedium)
            Text("Not affiliated with or endorsed by ESnet, Lawrence Berkeley National Laboratory or Google. " +
                "Android is a trademark of Google LLC.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onLicences, modifier = Modifier.testTag("openLicences")) { Text("View licences") }
        }
    }
}

/** Newest release open; every older one collapsed under its header — tap to expand or collapse. */
@Composable
fun ChangelogList(changelog: List<ChangelogEntry>, installed: String) {
    val latest = changelog.firstOrNull()?.version
    var open by rememberSaveable { mutableStateOf(setOfNotNull(latest).toList()) }
    changelog.forEachIndexed { i, e ->
        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
        val expanded = e.version in open
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "Collapse" else "Expand") {
                open = if (expanded) open - e.version else open + e.version
            }.padding(vertical = 4.dp).testTag("changelog_${e.version}"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(e.version, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            e.date?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (e.version == installed) Text("installed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            if (!expanded) Text("${e.items.size} change${if (e.items.size == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(expanded) {
            Column {
                e.items.forEach { item ->
                    Row(Modifier.padding(start = 4.dp)) {
                        Text("•  ", style = MaterialTheme.typography.bodyMedium)
                        Text(item, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
