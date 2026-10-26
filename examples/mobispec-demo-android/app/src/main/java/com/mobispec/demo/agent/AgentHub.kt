package com.mobispec.demo.agent

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.mobispec.demo.MainActivity
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
        _race.value = AgentRace(scenario.id, task, methods.map { EpisodeState(task.id, it.id, it.title) }, true)
        job = scope.launch {
            val runner = BinaryRunner(app)
            for ((i, m) in methods.withIndex()) {
                if (i > 0) delay(5_000)   // let the target app settle and the previous driver release the NPU
                // later methods read the first (AR) run's screens, so every run decodes exactly the same inputs
                val reference = if (i > 0) _race.value?.episodes?.first()?.takeIf { it.inputs.isNotEmpty() } else null
                AgentEpisode(app, service, runner, spec, task, m, modelsRoot, reference) { st ->
                    val r = _race.value ?: return@AgentEpisode
                    _race.value = r.copy(episodes = r.episodes.toMutableList().also { it[i] = st })
                    service.showHud(hudText(_race.value!!, i))
                }.run()
            }
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

    fun stop() {
        job?.cancel()
        AgentService.instance?.hideHud()
        _race.value = _race.value?.copy(running = false)
    }

    private val PHASES = mapOf("loading" to "모델 불러오는 중", "warmup" to "준비 중", "observe" to "화면 읽는 중",
        "think" to "화면 이해 중", "decode" to "생성 중", "act" to "동작 중", "verify" to "확인 중", "done" to "완료", "failed" to "실패")

    private fun hudText(r: AgentRace, i: Int): String {
        val e = r.episodes[i]
        val now = SystemClock.elapsedRealtime()
        val elapsed = if (e.startedAt > 0) (e.episodeMs ?: (now - e.startedAt)) / 1000.0 else 0.0
        val lines = mutableListOf(
            "${e.methodTitle} - ${r.task.title}",
            "스텝 ${e.stepNo}/${r.task.maxSteps} - ${PHASES[e.phase] ?: e.phase} - " + "%.1f s".format(elapsed),
        )
        e.steps.lastOrNull()?.let { s ->
            // only numbers go through format: an action's text may hold a '%' ("79%" once crashed the episode)
            lines += "직전: ${s.action}  " + "(%d tok, decode %.1f tok/s, AL %.2f)".format(
                s.tokens, if (s.decodeMs > 0) s.tokens / (s.decodeMs / 1000) else 0.0, s.al) +
                when (s.same) { true -> " - AR과 같은 토큰"; false -> " - AR과 다름"; null -> "" }
        }
        if (e.phase == "decode") lines += e.live.takeLast(140)
        // where the earlier (AR) run was at this moment, from its recorded step ends
        if (i > 0 && e.startedAt > 0) {
            val ar = r.episodes[0]
            val at = (now - e.startedAt)
            val arStep = ar.steps.count { it.endMs <= at }
            lines += "${ar.methodTitle}는 이 시각에 스텝 $arStep/${ar.steps.size} 완료" +
                (ar.episodeMs?.let { " (전체 " + "%.1f s".format(it / 1000.0) + ")" } ?: "")
        }
        return lines.joinToString("\n")
    }
}
