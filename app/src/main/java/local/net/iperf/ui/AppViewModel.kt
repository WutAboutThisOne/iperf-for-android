package local.net.iperf.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import local.net.iperf.core.NetType
import local.net.iperf.core.NetworkSnapshot
import local.net.iperf.core.OptionsDraft
import local.net.iperf.data.Export
import local.net.iperf.data.HistoryStore
import local.net.iperf.data.Prefs
import local.net.iperf.data.Server
import local.net.iperf.data.ServerStore
import local.net.iperf.data.TestRecord
import local.net.iperf.net.NetworkProbe
import local.net.iperf.run.ProcessIperfEngine
import local.net.iperf.run.RunController
import local.net.iperf.run.RunState
import local.net.iperf.ui.test.SsidHint
import local.net.iperf.ui.test.TestUi
import kotlinx.coroutines.launch
import local.net.iperf.core.OneTimePort
import local.net.iperf.core.effectivePort
import local.net.iperf.core.resolvePortChoice
import local.net.iperf.data.KeystoreCipher
import local.net.iperf.data.SecretStore
import local.net.iperf.data.ServerSecrets
import local.net.iperf.manager.ManagerApi
import local.net.iperf.manager.ManagerClient
import local.net.iperf.manager.ManagerController
import local.net.iperf.manager.PortAvailability
import java.io.File
import local.net.iperf.AppContainer
import local.net.iperf.data.ActivityEntries
import local.net.iperf.data.ActivityLogStore
import local.net.iperf.data.LogAction
import local.net.iperf.data.LogRedactor
import local.net.iperf.manager.ActivityLogger
import local.net.iperf.manager.InstanceStatus
import local.net.iperf.manager.ManagedInstance
import local.net.iperf.manager.StartAndTest
import local.net.iperf.manager.StartTestProgress
import local.net.iperf.manager.StartTestResult
import local.net.iperf.manager.tcpReachable

/** Port prompt state for one server. */
data class PortPromptUi(
    val server: Server,
    val managed: Boolean = false,
    val loadingPorts: Boolean = false,
    val ports: PortAvailability? = null,
    val portsError: String? = null,
    val working: Boolean = false,
    val error: String? = null,
)

/** Offer to stop an instance that Start & test started, once its test has ended. */
data class StopOffer(val server: Server, val port: Int)

private data class StartTestSession(val serverId: String, val port: Int, val startedByUs: Boolean, val testStarted: Boolean = false)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val container = AppContainer.get(app)
    private val servers = container.servers
    private val secrets = container.secrets
    private val history = container.history
    private val prefs = container.prefs
    private val probe = container.probe
    private val runner = container.runner
    val activityLog = container.activityLog
    private val tracker = container.reservations
    private val _now = MutableStateFlow(System.currentTimeMillis())
    /** Ticks once a second while this phone holds a reservation, for the time-left timers. */
    val now: StateFlow<Long> = _now

    private val draft = MutableStateFlow(prefs.draft)
    private val selectedId = MutableStateFlow(prefs.selectedServerId)
    private val invalid = MutableStateFlow<OptionsDraft.Parsed.Invalid?>(null)
    private val network = MutableStateFlow<NetworkSnapshot?>(null)
    private val ssidHint = MutableStateFlow(SsidHint.NONE)
    private val oneTime = MutableStateFlow<OneTimePort?>(null)
    private val _portPrompt = MutableStateFlow<PortPromptUi?>(null)
    val portPrompt: StateFlow<PortPromptUi?> = _portPrompt
    private val _manager = MutableStateFlow<ManagerController?>(null)
    val manager: StateFlow<ManagerController?> = _manager
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice
    val secretMap: StateFlow<Map<String, ServerSecrets>> = secrets.items

    // ---- Start & test ----
    private var managerServer: Server? = null
    private var session: StartTestSession? = null
    private val _startTest = MutableStateFlow<StartTestProgress?>(null)
    val startTest: StateFlow<StartTestProgress?> = _startTest
    private val _stopOffer = MutableStateFlow<StopOffer?>(null)
    val stopOffer: StateFlow<StopOffer?> = _stopOffer
    private val _goToTest = MutableStateFlow(0L)
    /** Bumped when the UI should switch to the Test tab. */
    val goToTest: StateFlow<Long> = _goToTest
    private val _autoStart = MutableStateFlow(0L)
    /** Bumped when the UI should start the test (through its notification-permission check). */
    val autoStartRequest: StateFlow<Long> = _autoStart
    private val _autoStartPref = MutableStateFlow(prefs.autoStartTest)
    val autoStartTest: StateFlow<Boolean> = _autoStartPref
    /** Overridable in tests. */
    internal var reachability: suspend (String, Int) -> Boolean = { h, p -> tcpReachable(h, p) }

    val records: StateFlow<List<TestRecord>> = history.items
    val serverList: StateFlow<List<Server>> = servers.items
    val selectedServerId: StateFlow<String?> = selectedId
    val runState: StateFlow<RunState> = runner.state

    private data class Inputs(val d: OptionsDraft, val id: String?, val inv: OptionsDraft.Parsed.Invalid?, val net: NetworkSnapshot?, val hint: SsidHint)

    private val extendBlocked = MutableStateFlow(prefs.extendUnsupported)
    private data class ResInputs(val ot: OneTimePort?, val mine: List<local.net.iperf.data.MyReservation>, val now: Long, val blocked: Set<String>)

    val testUi: StateFlow<TestUi> = combine(
        combine(draft, selectedId, invalid, network, ssidHint) { d, id, inv, net, hint -> Inputs(d, id, inv, net, hint) },
        servers.items, runner.state,
        combine(oneTime, tracker.items, _now, extendBlocked) { ot, mine, now, blocked -> ResInputs(ot, mine, now, blocked) },
    ) { i, list, run, r ->
        val sel = list.firstOrNull { it.id == i.id } ?: list.firstOrNull()
        val ot = r.ot?.takeIf { it.serverId == sel?.id }
        val mine = ot?.takeIf { it.reserved }?.let { o -> r.mine.firstOrNull { it.serverId == o.serverId && it.port == o.port } }
        TestUi(list, sel, i.d, i.inv, run, i.net, i.hint, oneTime = ot,
            reservedUntilMs = mine?.expiresAtMs, canExtend = mine != null && sel?.id !in r.blocked, nowMs = r.now)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TestUi())

    init {
        // Timers: tick while anything is reserved from this phone.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (tracker.items.value.isNotEmpty()) _now.value = System.currentTimeMillis()
            }
        }
        // A reservation released elsewhere (Manager screen, Release all mine, the notification): drop it from the
        // Test tab too, so the end of the run doesn't try to release it again.
        viewModelScope.launch {
            var before = tracker.items.value.map { it.serverId to it.port }.toSet()
            tracker.items.collect { list ->
                val nowSet = list.map { it.serverId to it.port }.toSet()
                val ot = oneTime.value
                if (ot != null && ot.reserved && (ot.serverId to ot.port) in before && (ot.serverId to ot.port) !in nowSet) {
                    oneTime.value = if (runner.isRunning) ot.copy(reserved = false) else null
                }
                if (container.runReservation.value?.let { (it.serverId to it.port) !in nowSet } == true) container.runReservation.value = null
                before = nowSet
                _manager.value?.syncMine()
            }
        }
        viewModelScope.launch { container.notices.collect { _notice.value = it } }
        // After a run ends: release a port we reserved for it; a one-time port is used up by a completed run.
        viewModelScope.launch {
            var wasRunning = false
            runner.state.collect { st ->
                val running = st is RunState.Running
                if (running && !wasRunning) onRunStarted(st as RunState.Running)
                if (wasRunning && !running) { onRunEnded(st); refreshNetwork() }
                wasRunning = running
            }
        }
    }

    private fun sessionFor(port: Int?): StartTestSession? {
        val ot = oneTime.value ?: return null
        return session?.takeIf { it.serverId == ot.serverId && it.port == ot.port && (port == null || port == it.port) }
    }

    private fun onRunStarted(st: RunState.Running) {
        val ot = oneTime.value
        if (ot != null && ot.reserved && ot.port == st.options.port && tracker.find(ot.serverId, ot.port) != null)
            container.runReservation.value = local.net.iperf.RunReservation(ot.serverId, ot.port)
        val ss = sessionFor(st.options.port) ?: return
        session = ss.copy(testStarted = true)
        servers.find(ss.serverId)?.let { logger(it).log(LogAction.TEST_START, listOf(ss.port), true, st.options.describe(), true) }
    }

    private fun onRunEnded(st: RunState) {
        container.runReservation.value = null
        val ot = oneTime.value ?: return
        val ss = sessionFor(null)?.takeIf { it.testStarted }
        val server = servers.find(ot.serverId)
        if (ss != null && server != null) {
            val (ok, detail) = when (st) {
                is RunState.Done -> true to ((if (st.record.stoppedEarly) "Stopped · " else "Finished · ") +
                    listOfNotNull(st.record.summary.upload?.mbps?.let { "up " + local.net.iperf.core.formatMbps(it) },
                        st.record.summary.download?.mbps?.let { "down " + local.net.iperf.core.formatMbps(it) }).joinToString(", "))
                is RunState.Failed -> false to "${st.error.title}: ${st.error.detail}"
                else -> true to "Stopped before any results"
            }
            logger(server).log(LogAction.TEST_END, listOf(ss.port), ok, detail, true)
        }
        if (ot.reserved) {
            oneTime.value = if (st is RunState.Done || ss != null) null else ot.copy(reserved = false)
            server?.let { s ->
                viewModelScope.launch {
                    releaseQuietly(s, ot.port, startAndTest = ss != null)
                    if (ss != null) { session = null; _stopOffer.value = StopOffer(s, ss.port) }
                }
            }
        } else {
            if (st is RunState.Done) oneTime.value = null
            // Start & test whose reservation was already released (e.g. from the notification): still offer Stop.
            if (ss != null && server != null) { session = null; oneTime.value = null; _stopOffer.value = StopOffer(server, ss.port) }
        }
    }

    private suspend fun releaseQuietly(server: Server, port: Int, startAndTest: Boolean = false) {
        val api = managerApi(server) ?: return
        _notice.value = try {
            api.release(port)
            tracker.remove(server.id, port)
            logger(server).log(LogAction.RELEASE, listOf(port), true, "Released after test", startAndTest)
            "Released port $port"
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if ((e as? local.net.iperf.manager.ManagerException)?.kind == local.net.iperf.manager.ManagerException.Kind.NOT_FOUND)
                tracker.remove(server.id, port)
            logger(server).log(LogAction.RELEASE, listOf(port), false, e.message, startAndTest)
            "Couldn't release port $port: ${e.message}"
        }
    }

    // ---- activity log ----
    fun logger(server: Server): ActivityLogger = container.logger(server)

    /** Extend this run's reservation by another 15 min (the manager is asked to reserve it again). */
    fun extendOneTime(): kotlinx.coroutines.Job? {
        val ot = oneTime.value?.takeIf { it.reserved } ?: return null
        val server = servers.find(ot.serverId) ?: return null
        val api = managerApi(server) ?: return null
        val minutes = ManagerClient.DEFAULT_RESERVE_MINUTES
        return viewModelScope.launch {
            _notice.value = try {
                api.reserve(ot.port, minutes)
                tracker.put(server.id, ot.port, minutes)
                val until = java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.LocalTime.now().plusMinutes(minutes.toLong()))
                logger(server).log(LogAction.EXTEND, listOf(ot.port), true, "Extended by $minutes min (until $until)", false)
                "Port ${ot.port} reserved until $until"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger(server).log(LogAction.EXTEND, listOf(ot.port), false, e.message, false)
                if ((e as? local.net.iperf.manager.ManagerException)?.kind == local.net.iperf.manager.ManagerException.Kind.CONFLICT) {
                    markExtendUnsupported(server.id)
                    "This manager won't extend a reservation that's still held, so Extend is now hidden for it. Release and reserve again instead."
                } else "Couldn't extend port ${ot.port}: ${e.message}"
            }
        }
    }

    private fun markExtendUnsupported(serverId: String) {
        prefs.extendUnsupported = prefs.extendUnsupported + serverId
        extendBlocked.value = prefs.extendUnsupported
    }

    fun clearActivity(serverId: String) = activityLog.clear(serverId)

    /** Writes the server's activity log to cache/exports and returns a share intent for it. */
    fun activityExportIntent(serverId: String, csv: Boolean): Intent? {
        val list = activityLog.forServer(serverId)
        if (list.isEmpty()) return null
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(java.time.LocalDateTime.now())
        val file = File(dir, "iperf-activity-$stamp." + if (csv) "csv" else "json")
        file.writeText(if (csv) ActivityLogStore.csv(list) else ActivityLogStore.json(list))
        val uri: Uri = FileProvider.getUriForFile(app, "${app.packageName}.exports", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (csv) "text/csv" else "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Export ${list.size} log entries")
    }

    // ---- Start & test ----
    fun consumeGoToTest() { _goToTest.value = 0 }
    fun consumeAutoStart() { _autoStart.value = 0 }
    fun setAutoStartTest(on: Boolean) { prefs.autoStartTest = on; _autoStartPref.value = on }
    fun dismissStartTestError() { _startTest.value = null }
    fun dismissStopOffer() { _stopOffer.value = null }

    fun stopOfferedInstance() {
        val offer = _stopOffer.value ?: return
        _stopOffer.value = null
        val api = managerApi(offer.server) ?: return
        viewModelScope.launch {
            _notice.value = try {
                api.stop(offer.port)
                logger(offer.server).log(LogAction.STOP, listOf(offer.port), true, "Stopped after test", true)
                "Port ${offer.port} stopped"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger(offer.server).log(LogAction.STOP, listOf(offer.port), false, e.message, true)
                "Couldn't stop port ${offer.port}: ${e.message}"
            }
        }
    }

    /** Start (if stopped) → reserve → wait until reachable → load as this run's port → Test tab (→ start, if enabled). */
    fun startAndTest(inst: ManagedInstance): kotlinx.coroutines.Job? {
        val server = managerServer ?: return null
        val api = managerApi(server) ?: return null
        if (runner.isRunning) { _notice.value = "A test is already running"; return null }
        if (_startTest.value?.running == true) return null
        val alreadyRunning = inst.status == InstanceStatus.RUNNING || inst.status == InstanceStatus.DEGRADED
        val log = logger(server)
        return viewModelScope.launch {
            _startTest.value = StartTestProgress(inst.port,
                if (alreadyRunning) "Reserving port ${inst.port}…" else "Starting port ${inst.port}…")
            val r = StartAndTest(api, log, server.host, reachable = reachability)
                .prepare(inst.port, alreadyRunning) { step -> _startTest.value = StartTestProgress(inst.port, step) }
            when (r) {
                is StartTestResult.Failed -> {
                    _startTest.value = StartTestProgress(inst.port, "", error = r.message)
                    _notice.value = r.message
                    _manager.value?.refresh()
                }
                is StartTestResult.Ready -> {
                    tracker.put(server.id, r.port, ManagerClient.DEFAULT_RESERVE_MINUTES)
                    previousReservationRelease(server.id, except = r.port)
                    setSelected(server)
                    oneTime.value = OneTimePort(server.id, r.port, reserved = true, reserveMinutes = ManagerClient.DEFAULT_RESERVE_MINUTES)
                    session = StartTestSession(server.id, r.port, r.startedByUs)
                    log.log(LogAction.LOAD_PORT, listOf(r.port), true, "Loaded as this run's port", true)
                    _startTest.value = null
                    closeManager()
                    _goToTest.value = System.nanoTime()
                    if (prefs.autoStartTest) _autoStart.value = System.nanoTime()
                }
            }
        }
    }

    fun consumeNotice() { _notice.value = null }

    fun secretsFor(id: String?): ServerSecrets = secrets.get(id)

    /** Null when the server has no manager configured or the URL is unusable. */
    fun managerApi(server: Server): ManagerApi? = apiFactory(server)

    /** Overridable in tests (a mock manager). Shared with the notification's Release, via the container. */
    internal var apiFactory: (Server) -> ManagerApi?
        get() = container.managerApiFactory
        set(v) { container.managerApiFactory = v }

    fun managerError(server: Server): String? {
        val base = server.managerBaseUrl ?: return "No manager URL set"
        return runCatching { ManagerClient.validateBase(base); null }.getOrElse { it.message }
    }

    // ---- port prompt ----
    fun askPort(server: Server) {
        val api = managerApi(server)
        _portPrompt.value = PortPromptUi(server, managed = api != null, loadingPorts = api != null)
        if (api != null) viewModelScope.launch {
            val r = runCatching { api.availablePorts() }
            _portPrompt.value = _portPrompt.value?.takeIf { it.server.id == server.id }?.copy(
                loadingPorts = false, ports = r.getOrNull(), portsError = r.exceptionOrNull()?.message,
            )
        }
    }

    fun dismissPortPrompt() { _portPrompt.value = null }

    /** Applies the prompt: select the server, and use [port] for this run only (optionally reserving it first). */
    fun choosePort(server: Server, port: Int, reserve: Boolean) {
        val choice = resolvePortChoice(server.id, server.port, port, reserve, ManagerClient.DEFAULT_RESERVE_MINUTES)
        if (!reserve) {
            previousReservationRelease(server.id)
            setSelected(server); oneTime.value = choice; _portPrompt.value = null
            return
        }
        val api = managerApi(server) ?: return
        _portPrompt.value = _portPrompt.value?.copy(working = true, error = null)
        viewModelScope.launch {
            try {
                api.reserve(port, ManagerClient.DEFAULT_RESERVE_MINUTES)
                tracker.put(server.id, port, ManagerClient.DEFAULT_RESERVE_MINUTES)
                logger(server).log(LogAction.RESERVE, listOf(port), true, "Reserved for ${ManagerClient.DEFAULT_RESERVE_MINUTES} min (port prompt)", false)
                previousReservationRelease(server.id, except = port)
                setSelected(server); oneTime.value = choice; _portPrompt.value = null
                _notice.value = "Port $port reserved for ${ManagerClient.DEFAULT_RESERVE_MINUTES} min"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger(server).log(LogAction.RESERVE, listOf(port), false, e.message, false)
                _portPrompt.value = _portPrompt.value?.copy(working = false, error = e.message)
            }
        }
    }

    private fun setSelected(server: Server) {
        selectedId.value = server.id; prefs.selectedServerId = server.id; invalid.value = null
    }

    private fun previousReservationRelease(newServerId: String, except: Int? = null) {
        val prev = oneTime.value ?: return
        oneTime.value = null
        if (prev.reserved && !(prev.serverId == newServerId && prev.port == except)) {
            servers.find(prev.serverId)?.let { s -> viewModelScope.launch { releaseQuietly(s, prev.port) } }
        }
    }

    /** Drop the one-time port (releasing its reservation, if any). */
    fun clearOneTimePort() {
        val ot = oneTime.value ?: return
        oneTime.value = null
        if (session?.port == ot.port && session?.serverId == ot.serverId) session = null
        if (ot.reserved) servers.find(ot.serverId)?.let { s -> viewModelScope.launch { releaseQuietly(s, ot.port) } }
    }

    // ---- manager screen ----
    fun openManager(server: Server) {
        val api = managerApi(server) ?: run { _notice.value = managerError(server); return }
        _manager.value?.closeLogs()
        managerServer = server
        _startTest.value = null
        _manager.value = ManagerController(api, viewModelScope, server.title, server.managerBaseUrl ?: "", activity = logger(server),
            serverId = server.id, tracker = tracker, extendSupported = server.id !in prefs.extendUnsupported,
            onExtendUnsupported = { markExtendUnsupported(server.id) }).also { it.refresh() }
    }

    fun closeManager() { _manager.value?.closeLogs(); _manager.value = null }

    val managedServer: Server? get() = managerServer

    fun refreshNetwork() {
        val snap = runCatching { probe.basic() }.getOrNull()
        network.value = snap
        ssidHint.value = when {
            snap?.type != NetType.WIFI || snap.ssid != null || prefs.ssidHintDismissed -> SsidHint.NONE
            !probe.hasLocationPermission() -> if (prefs.locationAsked) SsidHint.DENIED else SsidHint.ASK
            !probe.locationEnabled() -> SsidHint.LOCATION_OFF
            else -> SsidHint.NONE
        }
    }

    fun onLocationAsked() { prefs.locationAsked = true }
    val notificationsAsked: Boolean get() = prefs.notificationsAsked
    fun onNotificationsAsked() { prefs.notificationsAsked = true }

    /** Validates the options without starting, so the notification prompt only appears for a test that will run. */
    /** Current server + one-time port, read from the source flows (not the derived [testUi], which can lag). */
    private fun selectedForRun(): Pair<Server?, Int?> {
        val list = servers.items.value
        val sel = list.firstOrNull { it.id == selectedId.value } ?: list.firstOrNull()
        return sel to sel?.let { effectivePort(it.id, it.port, oneTime.value) }
    }

    fun canStart(): Boolean {
        val (sel, port) = selectedForRun()
        val parsed = draft.value.toOptions(sel?.host, port)
        if (parsed is OptionsDraft.Parsed.Invalid) { invalid.value = parsed; return false }
        return true
    }
    fun dismissSsidHint() { prefs.ssidHintDismissed = true; ssidHint.value = SsidHint.NONE }

    fun editDraft(d: OptionsDraft) { draft.value = d; prefs.draft = d; invalid.value = null }

    fun select(server: Server) {
        if (selectedId.value != server.id && oneTime.value?.serverId != server.id) clearOneTimePort()
        selectedId.value = server.id; prefs.selectedServerId = server.id; invalid.value = null
    }

    fun saveServer(server: Server, secret: ServerSecrets = ServerSecrets()) {
        val saved = servers.upsert(server)
        secrets.put(saved.id, secret)
        if (server.id.isBlank() || selectedId.value == null) select(saved)
    }

    fun deleteServer(server: Server) {
        if (oneTime.value?.serverId == server.id) clearOneTimePort()
        servers.delete(server.id)
        secrets.delete(server.id)
        if (selectedId.value == server.id) { selectedId.value = null; prefs.selectedServerId = null }
    }

    fun start() {
        val (sel, port) = selectedForRun()
        when (val parsed = draft.value.toOptions(sel?.host, port)) {
            is OptionsDraft.Parsed.Invalid -> invalid.value = parsed
            is OptionsDraft.Parsed.Ok -> {
                invalid.value = null
                runner.start(parsed.options, sel!!.title, secrets.get(sel.id).iperfAuth)
            }
        }
    }

    fun stop() = runner.stop()
    fun dismissResult() = runner.dismiss()
    fun deleteRecord(r: TestRecord) = history.delete(r.id)
    fun deleteAllRecords() = history.deleteAll()
    fun record(id: String?): TestRecord? = records.value.firstOrNull { it.id == id }

    /** Writes the export to cache/exports and returns a share intent for it. */
    fun exportIntent(csv: Boolean): Intent? {
        val list = records.value
        if (list.isEmpty()) return null
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, Export.fileName(if (csv) "csv" else "json"))
        file.writeText(if (csv) Export.csv(list) else Export.json(list))
        val uri: Uri = FileProvider.getUriForFile(app, "${app.packageName}.exports", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (csv) "text/csv" else "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Export ${list.size} results")
    }
}
