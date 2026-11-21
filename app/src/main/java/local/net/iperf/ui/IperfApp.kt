package local.net.iperf.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import local.net.iperf.ui.adaptive.LocalAdaptive
import local.net.iperf.ui.history.HistoryListDetail
import local.net.iperf.ui.test.TestLayout
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import local.net.iperf.BuildConfig
import local.net.iperf.core.OptionsDraft
import local.net.iperf.core.Changelog
import local.net.iperf.core.ChangelogEntry
import local.net.iperf.manager.InstanceAction
import local.net.iperf.ui.manager.ManagerActions
import local.net.iperf.ui.manager.ManagerContent
import local.net.iperf.ui.manager.ActivityLogContent
import local.net.iperf.ui.manager.ActivityLogMenu
import local.net.iperf.ui.servers.PortPromptDialog
import local.net.iperf.ui.servers.WebpageDialog
import local.net.iperf.util.Links
import local.net.iperf.util.SecureClipboard
import local.net.iperf.data.Server
import local.net.iperf.data.TestRecord
import local.net.iperf.run.RunState
import local.net.iperf.ui.history.HistoryActions
import local.net.iperf.ui.history.HistoryContent
import local.net.iperf.ui.history.HistoryDetailContent
import local.net.iperf.ui.servers.ServerActions
import local.net.iperf.ui.servers.ServerDialog
import local.net.iperf.ui.servers.ServersContent
import local.net.iperf.ui.test.TestActions
import local.net.iperf.ui.test.TestContent

enum class Tab(val label: String, val icon: ImageVector) {
    TEST("Test", Icons.Rounded.Speed),
    SERVERS("Servers", Icons.Rounded.Dns),
    HISTORY("History", Icons.Rounded.History),
}

@Composable
fun IperfApp(vm: AppViewModel) {
    val context = LocalContext.current
    val testUi by vm.testUi.collectAsStateWithLifecycle()
    val servers by vm.serverList.collectAsStateWithLifecycle()
    val selectedId by vm.selectedServerId.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.TEST) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var about by rememberSaveable { mutableStateOf(false) }
    var licences by rememberSaveable { mutableStateOf(false) }
    var licenceIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    // Licence texts are read from assets only while the Licences pages are open.
    val licenceList = if (licences) rememberLicenses() else emptyList()
    var activityFor by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClearActivity by remember { mutableStateOf(false) }
    val startTest by vm.startTest.collectAsStateWithLifecycle()
    val stopOffer by vm.stopOffer.collectAsStateWithLifecycle()
    val autoStartPref by vm.autoStartTest.collectAsStateWithLifecycle()
    val goToTest by vm.goToTest.collectAsStateWithLifecycle()
    val autoStartRequest by vm.autoStartRequest.collectAsStateWithLifecycle()
    val activityEntries by vm.activityLog.items.collectAsStateWithLifecycle()
    val portPrompt by vm.portPrompt.collectAsStateWithLifecycle()
    val manager by vm.manager.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val secretMap by vm.secretMap.collectAsStateWithLifecycle()
    val nowMs by vm.now.collectAsStateWithLifecycle()
    var webpageFor by remember { mutableStateOf<Server?>(null) }
    var editing by remember { mutableStateOf<Server?>(null) }
    var confirmDeleteServer by remember { mutableStateOf<Server?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val running = testUi.run is RunState.Running
    val adaptive = LocalAdaptive.current
    val wide = adaptive.wide

    // Keep the screen on while a test runs (no wake-lock permission needed).
    val view = LocalView.current
    DisposableEffect(running) { view.keepScreenOn = running; onDispose { view.keepScreenOn = false } }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.refreshNetwork() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onLocationAsked(); vm.refreshNetwork()
    }

    // Just-in-time notification permission (Android 13+), asked once, the first time a test starts. Tests run either way.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.onNotificationsAsked(); vm.start()
    }
    fun startWithNotificationCheck() {
        if (!vm.canStart()) return
        val needsAsk = Build.VERSION.SDK_INT >= 33 && !vm.notificationsAsked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }

    fun launch(intent: Intent) = try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {
        scope.launch { snackbar.showSnackbar("No app can handle that") }
    }

    val testActions = remember(vm) {
        object : TestActions {
            override fun select(server: Server) = vm.askPort(server)
            override fun changePort(server: Server) = vm.askPort(server)
            override fun clearOneTimePort() = vm.clearOneTimePort()
            override fun extendReservation() { vm.extendOneTime() }
            override fun addServer() { editing = Server(id = "", host = "") }
            override fun editDraft(draft: OptionsDraft) = vm.editDraft(draft)
            override fun start() = startWithNotificationCheck()
            override fun stop() = vm.stop()
            override fun dismissResult() = vm.dismissResult()
            override fun allowLocation() = locationLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            override fun openLocationSettings() { launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            override fun openAppSettings() {
                launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }
            override fun dismissSsidHint() = vm.dismissSsidHint()
        }
    }
    val serverActions = remember(vm) {
        object : ServerActions {
            override fun pick(server: Server) { tab = Tab.TEST; vm.askPort(server) }
            override fun webpage(server: Server) {
                if (vm.secretsFor(server.id).hasWebLogin) webpageFor = server
                else Links.webpage(server.webUrl)?.let(::launch)
            }
            override fun manage(server: Server) = vm.openManager(server)
            override fun add() { editing = Server(id = "", host = "") }
            override fun edit(server: Server) { editing = server }
            override fun delete(server: Server) { confirmDeleteServer = server }
        }
    }
    val historyActions = remember(vm) {
        object : HistoryActions {
            override fun open(record: TestRecord) { detailId = record.id }
            override fun delete(record: TestRecord) {
                vm.deleteRecord(record)
                scope.launch { snackbar.showSnackbar("Result deleted") }
            }
        }
    }

    val detail = detailId?.let { id -> records.firstOrNull { it.id == id } }
    // Unfolded History is list-detail: the selected result shows beside the list instead of replacing it.
    val historySplit = wide && tab == Tab.HISTORY && !about && manager == null && activityFor == null
    // Unfolded, a selected result only ever shows inside History's detail pane (it stays selected on other tabs).
    val fullDetail = detail.takeIf { !wide }
    val activityServer = activityFor?.let { id -> servers.firstOrNull { it.id == id } }
    val activityList = activityFor?.let { id -> activityEntries.filter { it.serverId == id } } ?: emptyList()
    fun goBack() = when {
        licenceIndex != null -> licenceIndex = null
        licences -> licences = false
        about -> about = false
        activityFor != null -> activityFor = null
        manager != null -> vm.closeManager()
        else -> detailId = null
    }
    BackHandler(enabled = about || fullDetail != null || manager != null || activityFor != null) { goBack() }
    LaunchedEffect(goToTest) { if (goToTest > 0) { vm.consumeGoToTest(); tab = Tab.TEST; detailId = null; activityFor = null } }
    LaunchedEffect(autoStartRequest) { if (autoStartRequest > 0) { vm.consumeAutoStart(); startWithNotificationCheck() } }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); vm.consumeNotice() } }
    val managerActions = remember(manager) {
        val m = manager
        object : ManagerActions {
            override fun refresh() { m?.refresh() }
            override fun act(port: Int, action: InstanceAction) { m?.act(port, action) }
            override fun startAll() { m?.startAll() }
            override fun stopAll() { m?.stopAll() }
            override fun openLogs(port: Int) { m?.openLogs(port) }
            override fun refreshLogs() { m?.refreshLogs() }
            override fun setAutoRefresh(on: Boolean) { m?.setAutoRefresh(on) }
            override fun closeLogs() { m?.closeLogs() }
            override fun startAndTest(instance: local.net.iperf.manager.ManagedInstance) { vm.startAndTest(instance) }
            override fun openActivity() { activityFor = vm.managedServer?.id }
            override fun setAutoStart(on: Boolean) = vm.setAutoStartTest(on)
            override fun dismissStartTestError() = vm.dismissStartTestError()
            override fun extend(port: Int) { m?.extend(port) }
            override fun releaseAllMine() { m?.releaseAllMine() }
        }
    }
    LaunchedEffect(detailId, detail) { if (detailId != null && detail == null) detailId = null }

    val showNav = !about && fullDetail == null && manager == null && activityFor == null
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(when {
                        licenceIndex != null -> licenceList.getOrNull(licenceIndex!!)?.title ?: "Licence"
                        licences -> "Open-source licences"
                        about -> "About"
                        activityFor != null -> "Activity log"
                        manager != null -> vm.managedServer?.title ?: "Manager"
                        fullDetail != null -> "Result"
                        tab == Tab.TEST -> "iPerf for Android"
                        else -> tab.label
                    })
                },
                navigationIcon = {
                    if (about || fullDetail != null || manager != null || activityFor != null) IconButton(onClick = { goBack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                actions = {
                    when {
                        about -> Unit
                        activityFor != null -> ActivityLogMenu(
                            enabled = activityList.isNotEmpty(),
                            onExport = { csv -> activityFor?.let { vm.activityExportIntent(it, csv) }?.let(::launch) },
                            onClear = { confirmClearActivity = true },
                        )
                        manager != null -> Unit
                        fullDetail != null -> IconButton(onClick = { vm.deleteRecord(fullDetail); detailId = null }) {
                            Icon(Icons.Rounded.DeleteOutline, "Delete result")
                        }
                        tab == Tab.HISTORY && records.isNotEmpty() -> HistoryMenu(
                            onExport = { csv -> vm.exportIntent(csv)?.let(::launch) },
                            onDeleteAll = { confirmDeleteAll = true },
                        )
                        else -> IconButton(onClick = { about = true }) { Icon(Icons.Rounded.Info, "About") }
                    }
                },
            )
        },
        bottomBar = {
            if (!wide && showNav) NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                        modifier = Modifier.testTag("tab_${t.name}"),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Row(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()) {
            if (wide && showNav) NavigationRail(Modifier.testTag("navRail")) {
                Tab.entries.forEach { t ->
                    NavigationRailItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                        modifier = Modifier.testTag("rail_${t.name}"),
                    )
                }
            }
            Box(Modifier.weight(1f).imePadding().fillMaxSize()) {
                when {
                    licenceIndex != null -> licenceList.getOrNull(licenceIndex!!)?.let { LicenceDetailContent(it) }
                    licences -> LicencesContent(licenceList, onOpen = { licenceIndex = it })
                    about -> AboutContent(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, rememberChangelog(), onLicences = { licences = true })
                    activityFor != null -> ActivityLogContent(activityServer?.title ?: vm.managedServer?.title ?: "this server", activityList)
                    manager != null -> ManagerRoute(manager!!, managerActions, startTest, autoStartPref, snackbar, wide, nowMs)
                    fullDetail != null -> HistoryDetailContent(fullDetail)
                    tab == Tab.TEST -> TestContent(testUi, testActions, layout = when {
                        adaptive.tabletop -> TestLayout.TABLETOP
                        wide -> TestLayout.SIDE_BY_SIDE
                        else -> TestLayout.COMPACT
                    })
                    tab == Tab.SERVERS -> ServersContent(servers, testUi.selected?.id ?: selectedId, serverActions, secrets = secretMap)
                    tab == Tab.HISTORY -> if (historySplit) HistoryListDetail(records, detail, historyActions) else HistoryContent(records, historyActions)
                }
            }
        }
    }

    editing?.let { s ->
        ServerDialog(s, vm.secretsFor(s.id.ifBlank { null }), onSave = { srv, sec -> vm.saveServer(srv, sec); editing = null },
            onDismiss = { editing = null })
    }
    portPrompt?.let { p ->
        PortPromptDialog(p, onChoose = { port, reserve -> vm.choosePort(p.server, port, reserve) }, onDismiss = vm::dismissPortPrompt)
    }
    webpageFor?.let { s ->
        WebpageDialog(
            s, vm.secretsFor(s.id),
            onOpen = { Links.webpage(s.webUrl)?.let(::launch) },
            onCopy = { value, sensitive, what ->
                SecureClipboard.copy(context, value, sensitive)
                scope.launch { snackbar.showSnackbar("$what copied — clears in about a minute") }
            },
            onDismiss = { webpageFor = null },
        )
    }
    confirmDeleteServer?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDeleteServer = null },
            title = { Text("Delete ${s.title}?") },
            text = { Text("${s.address} will be removed from saved servers. Past results stay in History.") },
            confirmButton = { TextButton(onClick = { vm.deleteServer(s); confirmDeleteServer = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDeleteServer = null }) { Text("Cancel") } },
        )
    }
    if (confirmClearActivity) AlertDialog(
        onDismissRequest = { confirmClearActivity = false },
        title = { Text("Clear activity log?") },
        text = { Text("All ${activityList.size} entries for ${activityServer?.title ?: "this server"} will be deleted from this phone. Export first if you want a copy.") },
        confirmButton = {
            TextButton(onClick = { activityFor?.let(vm::clearActivity); confirmClearActivity = false }, modifier = Modifier.testTag("confirmClearActivity")) { Text("Clear") }
        },
        dismissButton = { TextButton(onClick = { confirmClearActivity = false }) { Text("Cancel") } },
    )
    stopOffer?.let { offer ->
        AlertDialog(
            onDismissRequest = vm::dismissStopOffer,
            title = { Text("Stop instance?") },
            text = { Text("The test on port ${offer.port} has ended and its reservation was released. Stop the iperf3 instance on ${offer.server.title} too?") },
            confirmButton = { TextButton(onClick = vm::stopOfferedInstance, modifier = Modifier.testTag("confirmStopInstance")) { Text("Stop instance") } },
            dismissButton = { TextButton(onClick = vm::dismissStopOffer) { Text("Keep running") } },
        )
    }
    if (confirmDeleteAll) AlertDialog(
        onDismissRequest = { confirmDeleteAll = false },
        title = { Text("Delete all results?") },
        text = { Text("All ${records.size} saved results will be permanently deleted. Export first if you want a copy.") },
        confirmButton = { TextButton(onClick = { vm.deleteAllRecords(); confirmDeleteAll = false }) { Text("Delete all") } },
        dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ManagerRoute(
    controller: local.net.iperf.manager.ManagerController,
    actions: ManagerActions,
    startTest: local.net.iperf.manager.StartTestProgress?,
    autoStart: Boolean,
    snackbar: SnackbarHostState,
    wide: Boolean,
    nowMs: Long,
) {
    val ui by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); controller.consumeMessage() } }
    ManagerContent(ui, actions, startTest = startTest, autoStart = autoStart, wide = wide, nowMs = nowMs)
}

@Composable
fun HistoryMenu(onExport: (csv: Boolean) -> Unit, onDeleteAll: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "History options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Export JSON") }, leadingIcon = { Icon(Icons.Rounded.IosShare, null) },
                onClick = { open = false; onExport(false) })
            DropdownMenuItem(text = { Text("Export CSV") }, leadingIcon = { Icon(Icons.Rounded.IosShare, null) },
                onClick = { open = false; onExport(true) })
            DropdownMenuItem(text = { Text("Delete all", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { open = false; onDeleteAll() })
        }
    }
}

/** Components shown on the Licences page (the app itself first); full texts live in assets/licenses/. */
@Composable
private fun rememberLicenses(): List<LicenseText> {
    val context = LocalContext.current
    return remember {
        fun read(name: String) = runCatching { context.assets.open("licenses/$name").bufferedReader().readText() }.getOrDefault("")
        val apache = read("apache-2.0.txt")
        listOf(
            LicenseText("iPerf for Android ${BuildConfig.VERSION_NAME}", "Apache License 2.0",
                "Copyright 2026 The project authors. Not affiliated with or endorsed by ESnet, Lawrence Berkeley National " +
                    "Laboratory or Google. Android is a trademark of Google LLC.", apache),
            LicenseText("iperf3 3.22", "BSD-3-Clause (cJSON: MIT)",
                "By ESnet / Lawrence Berkeley National Laboratory, built from source with the Android NDK.", read("iperf3-LICENSE.txt")),
            LicenseText("OpenSSL 3.5.9", "Apache License 2.0",
                "Linked into iperf3 for server logins (RSA).", read("openssl-LICENSE.txt")),
            LicenseText("AndroidX, Jetpack Compose, Material 3, Material icons and WindowManager", "Apache License 2.0",
                "By The Android Open Source Project.", apache),
            LicenseText("Kotlin and kotlinx.coroutines", "Apache License 2.0", "By JetBrains s.r.o. and contributors.", apache),
        )
    }
}

@Composable
private fun rememberChangelog(): List<ChangelogEntry> {
    val context = LocalContext.current
    return remember { runCatching { Changelog.parse(context.assets.open("CHANGELOG.md").bufferedReader().readText()) }.getOrDefault(emptyList()) }
}
