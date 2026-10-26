package com.mobispec.demo.ui

import android.app.Application
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobispec.demo.agent.AgentHub
import com.mobispec.demo.agent.AgentTask
import com.mobispec.demo.device.BootLock
import com.mobispec.demo.device.DeviceMonitor
import com.mobispec.demo.device.DeviceStatus
import com.mobispec.demo.manifest.Backend
import com.mobispec.demo.manifest.DemoManifest
import com.mobispec.demo.manifest.Headline
import com.mobispec.demo.manifest.Kind
import com.mobispec.demo.manifest.Method
import com.mobispec.demo.manifest.Placeholders
import com.mobispec.demo.manifest.Prompt
import com.mobispec.demo.manifest.Role
import com.mobispec.demo.manifest.Scenario
import com.mobispec.demo.run.BinaryRunner
import com.mobispec.demo.run.Lossless
import com.mobispec.demo.run.RunBuilder
import com.mobispec.demo.run.RunEvent
import com.mobispec.demo.run.RunRecord
import com.mobispec.demo.run.RunStore
import com.mobispec.demo.run.StepRec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

enum class Phase { IDLE, COOLING, LOADING, PREFILL, DECODING, DONE, FAILED }

/** Which instant two runs are lined up on: the first decoded token, or the prompt submission. */
enum class Align { DECODE, E2E }

data class PaneState(
    val title: String,
    val role: Role,
    val headline: Headline = Headline.DECODE,
    val phase: Phase = Phase.IDLE,
    val steps: List<StepRec> = emptyList(),
    val marks: Map<String, Long> = emptyMap(),
    val done: Map<String, Double> = emptyMap(),
    val replay: Boolean = false,
    val recordedAtMs: Long? = null,
    val message: String? = null,
    val record: RunRecord? = null,
) {
    val tokens: Int get() = steps.sumOf { it.ids.size }
    val speculative: Boolean get() = role == Role.SD

    /** Running decode rate, from the first token to the latest one. */
    val liveTps: Double? get() {
        if (steps.size < 2) return null
        val dt = steps.last().tUs - steps.first().tUs
        return if (dt > 0) (tokens - steps.first().ids.size) / (dt / 1e6) else null
    }
    val liveAl: Double? get() = if (steps.size > 1) (tokens - steps.first().ids.size).toDouble() / (steps.size - 1) else null

    /** The number the scenario leads with: the driver's decode rate, or first-to-last-token generation rate. */
    val shownTps: Double? get() = if (headline == Headline.GENERATION) liveTps else done["tps"] ?: liveTps
}

data class RaceState(
    val scenarioId: String,
    val promptId: String,
    val sdMethodId: String,
    val ar: PaneState,
    val sd: PaneState,
    val align: Align,
    val headline: Headline,
    val running: Boolean = false,
    val lossless: Lossless? = null,
    val note: String? = null,
) {
    /** Final ratio once both runs are recorded; while SD decodes, its running rate against AR's. */
    val xar: Double? get() {
        val a = ar.record?.headlineTps(headline) ?: ar.shownTps ?: return null
        val s = sd.record?.headlineTps(headline) ?: sd.shownTps ?: return null
        return if (a > 0) s / a else null
    }
}

data class BoardRow(val method: Method, val pane: PaneState)

data class BoardState(
    val scenarioId: String,
    val promptId: String,
    val headline: Headline,
    val rows: List<BoardRow>,
    val running: Boolean = false,
    val note: String? = null,
)

data class Settings(
    val headroomLimit: Float = 0.6f,   // wait before a run while the platform forecast is above this
    val batteryHotC: Double = 38.0,    // or while the battery is this warm (the S26 gives no headroom forecast)
    val minGapSec: Int = 30,           // minimum idle time between back-to-back board runs
    val align: Align = Align.DECODE,
    val tree: Boolean = true,          // ask the drivers for per-step trees (MAST only so far)
)

sealed interface Screen {
    data object Home : Screen
    data class ScenarioScreen(val id: String) : Screen
    data object Race : Screen
    data object Board : Screen
    data class Log(val record: RunRecord) : Screen
    data class AgentReplay(val scenarioId: String) : Screen
}

data class UiState(
    val manifest: DemoManifest? = null,
    val manifestError: String? = null,
    val modelsDir: String = "",
    val missing: Map<String, List<String>> = emptyMap(),
    val device: DeviceStatus? = null,
    val pinned: Backend? = null,
    val screen: Screen = Screen.Home,
    val race: RaceState? = null,
    val board: BoardState? = null,
    val settings: Settings = Settings(),
    val skipCooldown: Boolean = false,
)

private const val TAG = "MobiSpecDemo"

class DemoViewModel(app: Application) : AndroidViewModel(app) {
    private val runner = BinaryRunner(app)
    private val monitor = DeviceMonitor(app)
    private val bootLock = BootLock(app)
    private val store = RunStore(File(app.filesDir, "runs"))
    private val root: File = app.getExternalFilesDir(null) ?: app.filesDir
    private val modelsDir = File(root, "models")

    private val _ui = MutableStateFlow(UiState(modelsDir = modelsDir.path))
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var job: Job? = null

    init {
        reload()
        viewModelScope.launch {
            while (isActive) {
                val d = withContext(Dispatchers.IO) { monitor.read() }
                _ui.update { it.copy(device = d, pinned = bootLock.pinned(d.bootCount)) }
                delay(2000)
            }
        }
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            val f = File(root, "demo-manifest.json")
            val parsed = runCatching { DemoManifest.load(f) }
            val manifest = parsed.getOrNull()
            val missing = manifest?.scenarios?.associate { sc ->
                sc.id to sc.methods.flatMap { m -> m.files }.distinctBy { it.path }.mapNotNull { req ->
                    val file = File(sc.modelsRoot?.let { File(it) } ?: modelsDir, req.path)
                    when {
                        !file.exists() -> req.path
                        req.bytes != null && file.isFile && file.length() != req.bytes -> "${req.path} (size)"
                        else -> null
                    }
                } + sc.methods.map { it.bin }.distinct().filterNot { File(runner.libDir, it).exists() }.map { "lib: $it" }
            } ?: emptyMap()
            _ui.update {
                it.copy(
                    manifest = manifest, missing = missing,
                    manifestError = parsed.exceptionOrNull()?.let { e -> "${f.path}: ${e.message}" },
                )
            }
        }
    }

    fun go(screen: Screen) = _ui.update { it.copy(screen = screen) }
    fun settings(s: Settings) = _ui.update { it.copy(settings = s) }
    fun skipCooldown() = _ui.update { it.copy(skipCooldown = true) }
    fun resetBootLock() { bootLock.reset(); _ui.update { it.copy(pinned = null) } }
    fun clearHistory(scenarioId: String) { store.clear(scenarioId) }

    private fun modelsOf(sc: Scenario): File = sc.modelsRoot?.let { File(it) } ?: modelsDir

    fun modelsPath(sc: Scenario): String = modelsOf(sc).path

    /** Starts an agent race (AR first); returns why it cannot start, or null. */
    fun agentRace(sc: Scenario, task: AgentTask, methodIds: List<String>): String? {
        val dev = _ui.value.device
        if (dev != null && !bootLock.allows(dev.bootCount, sc.drafter)) {
            return "This boot already ran ${bootLock.pinned(dev.bootCount)}-draft demos. Reboot before running this scenario."
        }
        if (!AgentHub.serviceEnabled) return "Turn on the MobiSpec agent under Settings > Accessibility first."
        cancel()
        if (!AgentHub.start(getApplication(), sc, task, methodIds, modelsOf(sc).path)) return "An agent run is already going."
        if (dev != null) bootLock.pin(dev.bootCount, sc.drafter)
        Log.i(TAG, "AGENT ${sc.id} ${task.id} $methodIds")
        return null
    }

    fun scenario(id: String): Scenario? = _ui.value.manifest?.scenarios?.firstOrNull { it.id == id }
    fun cachedAr(sc: Scenario, prompt: Prompt, target: String): RunRecord? =
        sc.ar(target)?.let { store.latest(sc.id, prompt.id, it.id) }

    fun cancel() { job?.cancel(); job = null }

    // ---- race: AR vs one speculative method ------------------------------------------------

    /** replayAr: reuse the last AR run of this prompt and replay it alongside a live SD run. */
    fun race(sc: Scenario, prompt: Prompt, sd: Method, replayAr: Boolean) {
        cancel()
        val ar = sc.ar(sd.target) ?: return
        val align = _ui.value.settings.align
        _ui.update {
            it.copy(screen = Screen.Race, race = RaceState(sc.id, prompt.id, sd.id, PaneState(ar.title, Role.AR, sc.headline),
                PaneState(sd.title, Role.SD, sc.headline), align, sc.headline, running = true))
        }
        job = viewModelScope.launch {
            try {
                val cached = if (replayAr) cachedAr(sc, prompt, sd.target) else null
                if (cached == null) {
                    val arRec = execute(sc, ar, prompt) { p -> updateRace { it.copy(ar = p) } } ?: return@launch
                    updateRace { it.copy(ar = it.ar.copy(record = arRec)) }
                    val sdRec = execute(sc, sd, prompt, gapAfterMs = arRec.wallMs) { p -> updateRace { it.copy(sd = p) } }
                    finishRace(sc, sd, prompt, arRec, sdRec)
                } else {
                    updateRace { it.copy(ar = paneFrom(cached, sc.headline).copy(steps = emptyList(), phase = Phase.IDLE, replay = true)) }
                    var replayJob: Job? = null
                    val sdRec = coroutineScope {
                        execute(sc, sd, prompt, onMark = { name, firstStep ->
                            val trigger = if (align == Align.E2E) name == "loaded" else firstStep
                            if (trigger && replayJob == null) {
                                replayJob = launch { replay(cached, align, sc.headline) { p -> updateRace { it.copy(ar = p) } } }
                            }
                        }) { p -> updateRace { it.copy(sd = p) } }
                    }
                    replayJob?.join()
                    finishRace(sc, sd, prompt, cached, sdRec)
                }
            } finally {
                updateRace { it.copy(running = false) }
            }
        }
    }

    /** Replays both recorded runs of the current race on one clock. */
    fun replayRace() {
        val r = _ui.value.race ?: return
        val arRec = r.ar.record ?: return
        val sdRec = r.sd.record ?: return
        cancel()
        updateRace { it.copy(running = true) }
        job = viewModelScope.launch {
            try {
                coroutineScope {
                    launch { replay(arRec, r.align, r.headline) { p -> updateRace { it.copy(ar = p) } } }
                    launch { replay(sdRec, r.align, r.headline) { p -> updateRace { it.copy(sd = p) } } }
                }
            } finally {
                updateRace { it.copy(running = false) }
            }
        }
    }

    private fun finishRace(sc: Scenario, sd: Method, prompt: Prompt, arRec: RunRecord, sdRec: RunRecord?) {
        if (sdRec == null) return
        val ref = sc.reference(sd.target)?.let { m -> if (m.id == arRec.methodId) arRec else store.latest(sc.id, prompt.id, m.id) }
        val lossless = ref?.let { r -> Lossless.of(r.ids, sdRec.ids) }
        val note = listOfNotNull(
            when {
                ref == null -> "No same-graph reference run yet: run '${sc.reference(sd.target)?.title}' once to check losslessness."
                ref.methodId != arRec.methodId -> "Lossless check against ${ref.methodTitle} (same verify graph); speed against ${arRec.methodTitle}."
                else -> null
            },
            sc.losslessNote?.takeIf { lossless?.identical == false },
        ).joinToString("\n").ifEmpty { null }
        updateRace { it.copy(lossless = lossless, note = note) }
    }

    private fun updateRace(f: (RaceState) -> RaceState) = _ui.update { s -> s.race?.let { s.copy(race = f(it)) } ?: s }

    // ---- single run and method board -------------------------------------------------------

    fun runOne(sc: Scenario, prompt: Prompt, m: Method) {
        cancel()
        _ui.update { it.copy(screen = Screen.Board, board = BoardState(sc.id, prompt.id, sc.headline, listOf(BoardRow(m, PaneState(m.title, m.role, sc.headline))), running = true)) }
        job = viewModelScope.launch {
            try {
                execute(sc, m, prompt) { p -> updateBoardRow(m.id, p) }
            } finally {
                _ui.update { s -> s.board?.let { s.copy(board = it.copy(running = false)) } ?: s }
            }
        }
    }

    /** AR (and same-graph references), then every speculative method, one at a time; `only` limits it to these method ids. */
    fun board(sc: Scenario, prompt: Prompt, only: List<String>? = null) {
        cancel()
        val order = (sc.methods.filter { it.role != Role.SD } + sc.sdMethods).filter { only == null || it.id in only }
        _ui.update {
            it.copy(screen = Screen.Board, board = BoardState(sc.id, prompt.id, sc.headline,
                order.map { m -> BoardRow(m, PaneState(m.title, m.role, sc.headline)) }, running = true))
        }
        job = viewModelScope.launch {
            try {
                var lastWall = 0L
                for (m in order) {
                    val rec = execute(sc, m, prompt, gapAfterMs = lastWall) { p -> updateBoardRow(m.id, p) }
                    lastWall = rec?.wallMs ?: 0L
                }
            } finally {
                _ui.update { s -> s.board?.let { s.copy(board = it.copy(running = false)) } ?: s }
            }
        }
    }

    private fun updateBoardRow(id: String, p: PaneState) = _ui.update { s ->
        s.board?.let { b -> s.copy(board = b.copy(rows = b.rows.map { r -> if (r.method.id == id) r.copy(pane = p) else r })) } ?: s
    }

    // ---- adb-driven runs -------------------------------------------------------------------

    /**
     * Runs started by an intent (see MainActivity) so device checks need no taps. Every finished run
     * logs one RESULT line under the MobiSpecDemo tag; `race` plays AR against the first SD method id.
     */
    fun autorun(scenarioId: String, promptId: String?, methodIds: List<String>, race: Boolean, open: Boolean = false) {
        viewModelScope.launch {
            val manifest = withTimeoutOrNull(10_000) { ui.first { it.manifest != null || it.manifestError != null } }?.manifest
            val sc = manifest?.scenarios?.firstOrNull { it.id == scenarioId }
            if (sc?.kind == Kind.AGENT) {   // prompt names the task; methods run in the given order
                val task = sc.agent?.tasks?.firstOrNull { it.id == promptId }
                when {
                    task == null -> Log.e(TAG, "AUTORUN no task '$promptId' in ${sc.id}")
                    open -> go(Screen.ScenarioScreen(sc.id))
                    else -> agentRace(sc, task, methodIds)?.let { Log.e(TAG, "AUTORUN agent: $it") }
                }
                return@launch
            }
            val prompt = sc?.prompts?.firstOrNull { it.id == promptId } ?: sc?.prompts?.firstOrNull()
            if (sc == null || prompt == null) {
                Log.e(TAG, "AUTORUN no scenario '$scenarioId' / prompt '$promptId' (${_ui.value.manifestError ?: "manifest ok"})")
                return@launch
            }
            if (open) {   // only show the scenario screen; nothing runs
                go(Screen.ScenarioScreen(sc.id))
                return@launch
            }
            Log.i(TAG, "AUTORUN ${sc.id} ${prompt.id} $methodIds race=$race")
            val sd = sc.methods.firstOrNull { it.id in methodIds && it.role == Role.SD }
            if (race && sd != null) race(sc, prompt, sd, replayAr = false) else board(sc, prompt, methodIds.ifEmpty { null })
        }
    }

    private fun logResult(rec: RunRecord, saved: File?, failure: String?) {
        // org.json refuses NaN (toString() turns into null), and the thermal forecast is NaN when unsupported
        fun num(v: Double?): Any = v?.takeIf { it.isFinite() } ?: JSONObject.NULL
        fun obj(m: Map<String, Double>) = JSONObject().apply { m.forEach { (k, v) -> put(k, num(v)) } }
        val o = JSONObject().put("scenario", rec.scenarioId).put("prompt", rec.promptId).put("method", rec.methodId)
            .put("ok", rec.ok).put("exit", rec.exitCode).put("wall_ms", rec.wallMs).put("tokens", rec.tokens)
            .put("decode_tps", num(rec.decodeTps)).put("generation_tps", num(rec.generationTps)).put("al", num(rec.al))
            .put("ttft_ms", num(rec.ttftMs)).put("load_ms", num(rec.loadMs)).put("record", saved?.path ?: JSONObject.NULL)
            .put("failure", failure ?: JSONObject.NULL).put("done", obj(rec.done)).put("device", obj(rec.device))
        if (!rec.ok) rec.logTail.takeLast(20).forEach { Log.w(TAG, "TAIL $it") }
        Log.i(TAG, "RESULT $o")
    }

    // ---- execution ------------------------------------------------------------------------

    private suspend fun execute(
        sc: Scenario,
        m: Method,
        prompt: Prompt,
        gapAfterMs: Long = 0,
        onMark: (name: String, firstStep: Boolean) -> Unit = { _, _ -> },
        publish: (PaneState) -> Unit,
    ): RunRecord? {
        var pane = PaneState(m.title, m.role, sc.headline)
        fun emit(p: PaneState) { pane = p; publish(p) }

        // guards: boot-session drafter pin, battery, thermal forecast
        val dev0 = withContext(Dispatchers.IO) { monitor.read() }
        if (!bootLock.allows(dev0.bootCount, sc.drafter)) {
            emit(pane.copy(phase = Phase.FAILED, message = "This boot already ran ${bootLock.pinned(dev0.bootCount)}-draft demos. " +
                "Mixing NPU and GPU drafters in one boot has rebooted this phone; reboot first (or reset the pin in settings)."))
            Log.w(TAG, "RESULT {\"method\":\"${m.id}\",\"ok\":false,\"failure\":\"boot pin ${bootLock.pinned(dev0.bootCount)}\"}")
            return null
        }
        if (dev0.batteryPct in 0 until 10) {
            emit(pane.copy(phase = Phase.FAILED, message = "Battery ${dev0.batteryPct}% (< 10%): charge to 15% first."))
            Log.w(TAG, "RESULT {\"method\":\"${m.id}\",\"ok\":false,\"failure\":\"battery ${dev0.batteryPct}\"}")
            return null
        }
        cooldown(if (gapAfterMs > 0) _ui.value.settings.minGapSec else 0) { msg -> emit(pane.copy(phase = Phase.COOLING, message = msg)) }
        bootLock.pin(dev0.bootCount, sc.drafter)

        val spec = resolve(sc, m, prompt)
        val builder = RunBuilder(sc.id, m.id, m.title, prompt.id, m.role, m.target, runner.command(spec))
        emit(pane.copy(phase = Phase.LOADING, message = null))
        val devStart = withContext(Dispatchers.IO) { monitor.read() }
        try {
            runner.run(spec).collect { ev ->
                val firstStep = ev is RunEvent.Step && builder.steps.isEmpty()
                if (!builder.accept(ev)) return@collect
                when (ev) {
                    is RunEvent.Mark -> {
                        if (ev.name == "loaded") emit(pane.copy(phase = Phase.PREFILL, marks = LinkedHashMap(builder.marks)))
                        else emit(pane.copy(marks = LinkedHashMap(builder.marks)))
                        onMark(ev.name, false)
                    }
                    is RunEvent.Step -> {
                        emit(pane.copy(phase = Phase.DECODING, steps = ArrayList(builder.steps)))
                        if (firstStep) onMark("first_token", true)
                    }
                    is RunEvent.Done -> emit(pane.copy(done = builder.done))
                    is RunEvent.Failed -> emit(pane.copy(phase = Phase.FAILED, message = ev.message))
                    else -> {}
                }
            }
        } catch (e: CancellationException) {
            emit(pane.copy(phase = Phase.FAILED, message = "cancelled"))
            throw e
        }
        val rec = builder.build(devStart.snapshot())
        val ok = rec.ok
        val saved = if (ok) withContext(Dispatchers.IO) { store.save(rec) } else null
        logResult(rec, saved, builder.failure)
        emit(pane.copy(
            phase = if (ok) Phase.DONE else Phase.FAILED, steps = rec.steps, marks = rec.marks, done = rec.done,
            record = rec, recordedAtMs = rec.startedAtMs,
            message = if (ok) null else (builder.failure ?: "exit ${rec.exitCode}: ${rec.logTail.takeLast(3).joinToString(" | ")}"),
        ))
        return rec.takeIf { ok }
    }

    private suspend fun cooldown(minGapSec: Int, show: (String) -> Unit) {
        _ui.update { it.copy(skipCooldown = false) }
        val limit = _ui.value.settings.headroomLimit
        val t0 = SystemClock.elapsedRealtime()
        while (!_ui.value.skipCooldown) {
            val d = withContext(Dispatchers.IO) { monitor.read() }
            val waited = (SystemClock.elapsedRealtime() - t0) / 1000
            val hot = (!d.headroom.isNaN() && d.headroom > limit) || d.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ||
                d.batteryTempC >= _ui.value.settings.batteryHotC
            if (!hot && waited >= minGapSec) return
            show(buildString {
                append("Cooling down ${waited}s")
                if (minGapSec > 0) append(" / min ${minGapSec}s")
                if (!d.headroom.isNaN()) append(", headroom %.2f (limit %.2f)".format(d.headroom, limit))
                append(", battery %.1f C (limit %.0f C), thermal %s".format(d.batteryTempC, _ui.value.settings.batteryHotC, d.thermalLabel))
            })
            delay(2000)
        }
    }

    private fun resolve(sc: Scenario, m: Method, prompt: Prompt): BinaryRunner.Spec {
        val work = File(getApplication<Application>().cacheDir, "run").apply { mkdirs() }
        val promptFile = File(work, "prompt.txt")
        promptFile.writeText(sc.promptFormat?.replace("{prompt}", prompt.text) ?: prompt.text)
        val models = modelsOf(sc)
        val values = mapOf(
            "MODELS" to models.path,
            "LIB" to runner.libDir,
            "PROMPT_FILE" to promptFile.path,
            "N" to sc.nPredict.toString(),
            "FIXTURE" to (prompt.fixture?.let { File(models, it).path } ?: ""),
            "IMAGE" to (prompt.image?.let { File(models, it).path } ?: ""),
        )
        return BinaryRunner.Spec(
            bin = m.bin,
            args = m.args.map { Placeholders.resolve(it, values) },
            env = m.env.mapValues { Placeholders.resolve(it.value, values) },
            affinity = m.affinity,
            tree = _ui.value.settings.tree,
        )
    }

    // ---- replay ---------------------------------------------------------------------------

    private suspend fun replay(rec: RunRecord, align: Align, headline: Headline, publish: (PaneState) -> Unit) {
        val t0 = if (align == Align.E2E) rec.marks["loaded"] ?: rec.steps.first().tUs else rec.steps.first().tUs
        val base = SystemClock.elapsedRealtimeNanos() / 1000
        var pane = paneFrom(rec, headline).copy(steps = emptyList(), done = emptyMap(), replay = true,
            phase = if (align == Align.E2E) Phase.PREFILL else Phase.DECODING)
        publish(pane)
        rec.steps.forEachIndexed { i, s ->
            val due = base + (s.tUs - t0)
            val wait = due - SystemClock.elapsedRealtimeNanos() / 1000
            if (wait > 0) delay(wait / 1000)
            pane = pane.copy(phase = Phase.DECODING, steps = rec.steps.subList(0, i + 1))
            publish(pane)
        }
        publish(pane.copy(phase = Phase.DONE, done = rec.done))
    }

    private fun paneFrom(rec: RunRecord, headline: Headline) = PaneState(
        title = rec.methodTitle, role = rec.role, headline = headline, phase = Phase.DONE, steps = rec.steps, marks = rec.marks,
        done = rec.done, replay = true, recordedAtMs = rec.startedAtMs, record = rec,
    )
}
