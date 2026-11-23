package com.mobispec.demo.agent

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.mobispec.demo.MainActivity
import com.mobispec.demo.manifest.Role
import com.mobispec.demo.manifest.Scenario
import com.mobispec.demo.run.BinaryRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** A task run with each method in turn (AR first, so the SD run can show where AR was at the same moment). */
data class AgentRace(
    val scenarioId: String,
    val task: AgentTask,
    val episodes: List<EpisodeState>,
    val running: Boolean,
)

/**
 * Runs agent races in the accessibility service's lifetime rather than the activity's: while the agent works, the
 * app is in the background behind the app it drives.
 */
object AgentHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _race = MutableStateFlow<AgentRace?>(null)
    val race: StateFlow<AgentRace?> = _race
    private var job: Job? = null

    val serviceEnabled: Boolean get() = AgentService.instance != null

    /** False when the service is not enabled or a race is already running. */
    fun start(context: Context, scenario: Scenario, task: AgentTask, methodIds: List<String>, modelsRoot: String): Boolean {
        val service = AgentService.instance ?: return false
        val spec = scenario.agent ?: return false
        if (job?.isActive == true) return false
        val methods = methodIds.mapNotNull { id -> scenario.methods.firstOrNull { it.id == id } }
        if (methods.isEmpty()) return false
        val app = context.applicationContext
        _race.value = AgentRace(scenario.id, task, methods.map { EpisodeState(task.id, it.id, it.label) }, true)
        val sd = methods.map { it.role == Role.SD }
        job = scope.launch {
            val runner = BinaryRunner(app)
            var current = 0
            val clock = launch {   // the task clock runs between state changes too
                while (true) {
                    delay(100)
                    val r = _race.value ?: continue
                    val e = r.episodes[current]
                    if (e.startedAt > 0 && e.episodeMs == null) service.showHud(hud(r, current, sd[current]))
                }
            }
            for ((i, m) in methods.withIndex()) {
                current = i
                if (i > 0) delay(5_000)   // let the target app settle and the previous driver release the NPU
                // later methods read the first (AR) run's screens, so every run decodes exactly the same inputs
                val reference = if (i > 0) _race.value?.episodes?.first()?.takeIf { it.inputs.isNotEmpty() } else null
                AgentEpisode(app, service, runner, spec, task, m, modelsRoot, reference) { st ->
                    val r = _race.value ?: return@AgentEpisode
                    _race.value = r.copy(episodes = r.episodes.toMutableList().also { it[i] = st })
                    service.showHud(hud(_race.value!!, i, sd[i]))
                }.run()
            }
            clock.cancel()
            _race.value = _race.value?.copy(running = false)
            _race.value?.let(::logResults)   // here, not in the activity's view model, which may be recreated mid-race
            service.hideHud()
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("agent_result", true))
        }
        return true
    }

    private fun logResults(r: AgentRace) = r.episodes.forEach { e ->
        Log.i("MobiSpecDemo", "AGENT_RESULT " + JSONObject().put("scenario", r.scenarioId).put("task", e.taskId).put("method", e.methodId)
            .put("phase", e.phase).put("success", e.success ?: JSONObject.NULL).put("answer", e.answer ?: JSONObject.NULL)
            .put("steps", e.steps.size).put("episode_ms", e.episodeMs ?: JSONObject.NULL).put("error", e.error ?: JSONObject.NULL)
            .put("same", e.steps.count { it.same == true }).put("record", e.record?.path ?: JSONObject.NULL))
    }

    /**
     * The latest recorded race of this task with `sdId` (an SD episode and the AR episode it read its inputs from), as a
     * finished race, so it can be shown and replayed without running. False when there is none or a race is running.
     */
    fun loadRecorded(context: Context, scenario: Scenario, task: AgentTask, sdId: String): Boolean {
        if (job?.isActive == true) return false
        val dir = File(context.filesDir, "agent/${task.id}")
        val sd = dir.listFiles { f -> f.isDirectory && f.name.startsWith("$sdId-") }.orEmpty()
            .sortedByDescending { it.name.substringAfterLast('-').toLongOrNull() ?: 0L }
            .map { File(it, "episode.json") }
            .firstOrNull { f -> f.isFile && runCatching { JSONObject(f.readText()).optString("reference").let { r -> r.isNotEmpty() && File(r).isFile } }.getOrDefault(false) }
            ?: return false
        val ar = File(JSONObject(sd.readText()).getString("reference"))
        val eps = listOf(ar, sd).map { f -> runCatching { episode(f, scenario) }.getOrNull() ?: return false }
        _race.value = AgentRace(scenario.id, task, eps, false)
        return true
    }

    private fun episode(f: File, scenario: Scenario): EpisodeState {
        val o = JSONObject(f.readText())
        val method = o.getString("method")
        val steps = o.getJSONArray("steps").let { a ->
            (0 until a.length()).map { i ->
                val s = a.getJSONObject(i)
                val d = s.optJSONObject("done") ?: JSONObject()
                val action = s.optJSONObject("action")?.let { x -> AgentAction(x.keys().asSequence().associateWith { k -> x.get(k) }) }
                AgentStep(
                    n = s.getInt("step"), action = AgentSpec.summarise(action), reason = s.optString("reason"),
                    tokens = s.optJSONArray("ids")?.length() ?: 0, visionMs = d.optDouble("vision_us", 0.0) / 1e3,
                    prefillMs = d.optDouble("ttft_us", 0.0) / 1e3, decodeMs = d.optDouble("decode_us", 0.0) / 1e3, al = d.optDouble("al", 1.0),
                    observeMs = s.optLong("observe_ms"), actMs = s.optLong("act_ms"), endMs = s.optLong("end_ms"),
                    error = s.optString("act_error").takeIf { it.isNotEmpty() && it != "null" },
                    same = if (s.isNull("same_as_reference")) null else s.optBoolean("same_as_reference"),
                )
            }
        }
        val ok = o.optBoolean("success")
        return EpisodeState(
            taskId = o.getString("task"), methodId = method,
            methodTitle = scenario.methods.firstOrNull { it.id == method }?.label ?: method,
            phase = if (o.optString("verdict") == "failed") "failed" else "done", stepNo = steps.size, startedAt = 1,
            steps = steps, success = ok, answer = o.optString("answer").takeIf { it.isNotEmpty() && it != "null" },
            error = o.optString("error").takeIf { it.isNotEmpty() }, episodeMs = if (o.has("episode_ms")) o.getLong("episode_ms") else null,
            record = f, onReference = steps.any { it.same != null },
        )
    }

    fun stop() {
        job?.cancel()
        AgentService.instance?.hideHud()
        _race.value = _race.value?.copy(running = false)
    }

    private val PHASES = mapOf("loading" to "loading model", "warmup" to "warming up", "observe" to "reading the screen",
        "think" to "encoding the screen", "decode" to "generating", "act" to "acting", "verify" to "checking", "done" to "done",
        "failed" to "failed")

    private fun hud(r: AgentRace, i: Int, sd: Boolean): Hud {
        val e = r.episodes[i]
        val now = SystemClock.elapsedRealtime()
        val elapsed = if (e.startedAt > 0) (e.episodeMs ?: (now - e.startedAt)) / 1000.0 else 0.0
        val tokens = e.steps.sumOf { it.tokens }
        val decodeSec = e.steps.sumOf { it.decodeMs } / 1000
        val calls = e.steps.sumOf { s -> if (s.al > 0) s.tokens / s.al else 0.0 }
        val detail = when {
            e.phase == "decode" -> e.live.replace('\n', ' ').takeLast(140)
            else -> e.steps.lastOrNull()?.let { "last: ${it.action}" } ?: ""
        }
        // where the earlier (AR) run was at this moment, from its recorded step ends; the first run gets a line too,
        // so both bands have one height
        val note = if (i > 0 && e.startedAt > 0) {
            val ar = r.episodes[0]
            val at = now - e.startedAt
            "${ar.methodTitle} at this moment: step ${ar.steps.count { it.endMs <= at }}/${ar.steps.size}" +
                (ar.episodeMs?.let { " (task " + "%.1f s".format(it / 1000.0) + ")" } ?: "")
        } else if (i == 0 && r.episodes.size > 1) "baseline run; ${r.episodes[1].methodTitle.substringBefore(" (")} follows on the same screens" else null
        return Hud(
            method = e.methodTitle.substringBefore(" ("), sd = sd, task = r.task.title, elapsedSec = elapsed,
            step = e.stepNo, maxSteps = r.task.maxSteps, phase = PHASES[e.phase] ?: e.phase,
            tps = if (decodeSec > 0) tokens / decodeSec else null, al = if (calls > 0) tokens / calls else null,
            detail = detail, note = note,
        )
    }
}
