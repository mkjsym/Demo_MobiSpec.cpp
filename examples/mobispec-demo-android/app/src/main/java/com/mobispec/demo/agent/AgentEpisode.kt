package com.mobispec.demo.agent

import android.app.UiModeManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.BatteryManager
import android.os.SystemClock
import com.mobispec.demo.manifest.Method
import com.mobispec.demo.manifest.Placeholders
import com.mobispec.demo.run.BinaryRunner
import com.mobispec.demo.run.RunEvent
import com.mobispec.demo.run.Utf8Accumulator
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One agent step as the HUD and the result card show it; times are the driver's own (ms). */
data class AgentStep(
    val n: Int,
    val action: String,
    val reason: String,
    val tokens: Int,
    val visionMs: Double,
    val prefillMs: Double,   // preparation (vision included) and the target prefill, up to the first token
    val decodeMs: Double,
    val al: Double,
    val observeMs: Long,
    val actMs: Long,
    val endMs: Long,         // since the episode started, after the action settled
    val error: String? = null,
    val same: Boolean? = null,   // a run on the reference's inputs: tokens identical to the reference step
)

/** What the model saw at one step and what it answered: the input of a later run on the same screens. */
data class StepInput(val prompt: File, val image: File, val elements: List<Element>, val foreground: String, val signature: String, val ids: List<Int>)

data class EpisodeState(
    val taskId: String,
    val methodId: String,
    val methodTitle: String,
    val phase: String = "loading",   // loading, warmup, observe, think, decode, act, verify, done, failed
    val stepNo: Int = 0,
    val startedAt: Long = 0,         // elapsedRealtime of the first observation
    val live: String = "",           // the reply being decoded
    val liveTokens: Int = 0,
    val steps: List<AgentStep> = emptyList(),
    val success: Boolean? = null,
    val answer: String? = null,
    val error: String? = null,
    val episodeMs: Long? = null,
    val record: File? = null,
    val inputs: List<StepInput> = emptyList(),
    val onReference: Boolean = false,   // the model read the reference run's screens (see AgentEpisode)
)

/**
 * One task with one method: the served driver loads, takes an untimed warm-up request (the first GPU encode compiles
 * its kernels), then the agent loop of tools/agent_probe.py runs with the service's eyes and hands.
 *
 * With a [reference] run, every step still observes the live screen (same cost, and a check that it matches), but the
 * model reads the reference's prompt and screenshot of that step and the action uses the reference's element bounds.
 * Two live runs part on rendering alone (Settings' blurred search bar differed by 126 pixels between launches and the
 * model scrolled in one run and tapped in the other), and two resident drivers rebooted the phone, so this is how an SD
 * run gets exactly the AR run's inputs while it still drives the phone.
 */
class AgentEpisode(
    private val context: Context,
    private val service: AgentService,
    private val runner: BinaryRunner,
    private val spec: AgentSpec,
    private val task: AgentTask,
    private val method: Method,
    private val modelsRoot: String,
    private val reference: EpisodeState? = null,
    private val onState: (EpisodeState) -> Unit,
) {
    private var state = EpisodeState(task.id, method.id, method.title, onReference = reference != null)

    private fun update(f: (EpisodeState) -> EpisodeState) {
        state = f(state)
        onState(state)
    }

    suspend fun run(): EpisodeState {
        val out = File(context.filesDir, "agent/${task.id}/${method.id}-${System.currentTimeMillis()}").apply { mkdirs() }
        val values = mapOf("MODELS" to modelsRoot.trimEnd('/'), "N" to spec.screen.nPredict.toString())
        val served = runner.serve(BinaryRunner.Spec(method.bin, method.args.map { Placeholders.resolve(it, values) },
            method.env.mapValues { Placeholders.resolve(it.value, values) }, method.affinity))
        val stepsJson = JSONArray()
        try {
            update { it.copy(phase = "loading") }
            withTimeout(180_000) { untilMark(served, "loaded") }
            update { it.copy(phase = "warmup") }
            warmup(served, out)

            // A fresh task stack for each app the task uses, then the launcher. Each step waits for its window: a HOME
            // sent right after the relaunch raced it, and the next run started on the previous run's last page.
            for (pkg in task.stop) {
                service.startApp(pkg)
                untilForeground { it == pkg }
            }
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            val launcher = context.packageManager.resolveActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0,
            )?.activityInfo?.packageName
            untilForeground { it == launcher }

            val history = mutableListOf<String>()
            var prevSig: String? = null
            var answer: String? = null
            var terminal: String? = null
            val t0 = SystemClock.elapsedRealtime()
            update { it.copy(startedAt = t0, phase = "observe") }
            val limit = reference?.inputs?.size ?: task.maxSteps
            for (n in 1..limit) {
                val ts = SystemClock.elapsedRealtime()
                update { it.copy(phase = "observe", stepNo = n, live = "", liveTokens = 0) }
                service.settle()
                var screen = service.readScreen(spec.screen.skipPackages)
                for (retry in 1..5) {
                    if (screen != null && screen.elements.isNotEmpty()) break
                    delay(400); service.settle(); screen = service.readScreen(spec.screen.skipPackages)
                }
                if (screen == null) throw IllegalStateException("no active window")
                val sig = screen.elements.joinToString("|") { "${it.text ?: ""}~${it.desc ?: ""}" }
                if (prevSig != null && sig == prevSig && history.isNotEmpty()) history[history.lastIndex] += "  (this did NOT change the screen)"
                prevSig = sig
                val prompt = spec.prompt(task.goal, screen.foreground, screen.elements, history)
                val txt = File(out, "step%02d.txt".format(n)).apply { writeText(prompt) }
                val png = File(out, "step%02d.png".format(n))
                val frames = writeScreenshot(screen.windowId, png)
                val observeMs = SystemClock.elapsedRealtime() - ts
                val ref = reference?.inputs?.get(n - 1)
                val elements = ref?.elements ?: screen.elements
                val foreground = ref?.foreground ?: screen.foreground

                update { it.copy(phase = "think") }
                served.send(n, ref?.prompt ?: txt, ref?.image ?: png, spec.screen.nPredict)
                val reply = withTimeout(180_000) { collect(served) }   // a hung driver becomes a recorded failure
                val (reason, action) = AgentSpec.parseReply(reply.text)

                update { it.copy(phase = "act") }
                val ta = SystemClock.elapsedRealtime()
                var error: String? = if (ref != null && ref.signature != sig) "the live screen differs from the reference run's" else null
                val reopen = action?.type == "open_app" && service.resolvePackage(action.appName ?: "") == foreground
                if (reopen) error = "${spec.label(foreground)} is already open"   // tell the agent instead of a silent no-op
                else if (action != null) {
                    terminal = try { service.perform(action, elements) } catch (e: AgentService.ActionError) { error = e.message; null }
                    if (action.type == "answer") answer = action.text
                } else error = "could not parse an action"
                val now = SystemClock.elapsedRealtime()
                val d = reply.done
                val step = AgentStep(n, AgentSpec.summarise(action), reason, reply.ids.size, (d["vision_us"] ?: 0.0) / 1e3,
                    (d["ttft_us"] ?: 0.0) / 1e3, (d["decode_us"] ?: 0.0) / 1e3, d["al"] ?: 1.0, observeMs, now - ta, now - t0, error,
                    same = ref?.let { it.ids == reply.ids })
                val input = StepInput(ref?.prompt ?: txt, ref?.image ?: png, elements, foreground, sig, reply.ids)
                update { it.copy(steps = it.steps + step, inputs = it.inputs + input) }
                stepsJson.put(JSONObject().put("step", n).put("elements", screen.elements.size).put("foreground", screen.foreground)
                    .put("observe_ms", observeMs).put("frames", frames).put("act_ms", now - ta).put("end_ms", now - t0).put("text", reply.text)
                    .put("reason", reason).put("action", action?.let { JSONObject(it.fields) } ?: JSONObject.NULL)
                    .put("ids", JSONArray(reply.ids)).put("done", JSONObject(d.filterValues { it.isFinite() }))
                    .put("act_error", error ?: JSONObject.NULL).put("on_reference", ref != null)
                    .put("same_as_reference", step.same ?: JSONObject.NULL))
                history += AgentSpec.summarise(action) + (error?.let { " [FAILED: $it]" } ?: "") + " - " + reason
                if (terminal != null) break
            }
            val episodeMs = SystemClock.elapsedRealtime() - t0
            update { it.copy(phase = "verify", answer = answer, episodeMs = episodeMs) }
            val ok = verify(answer)
            val rec = File(out, "episode.json")
            rec.writeText(JSONObject().put("task", task.id).put("method", method.id).put("success", ok)
                .put("verdict", terminal?.let { "agent_$it" } ?: "max_steps").put("answer", answer ?: JSONObject.NULL)
                .put("episode_ms", episodeMs).put("reference", reference?.record?.path ?: JSONObject.NULL)
                .put("steps", stepsJson).toString(1))
            update { it.copy(phase = "done", success = ok, record = rec) }
        } catch (e: Exception) {
            val rec = File(out, "episode.json")
            runCatching {
                rec.writeText(JSONObject().put("task", task.id).put("method", method.id).put("success", false).put("verdict", "failed")
                    .put("error", e.message ?: e.toString()).put("phase", state.phase).put("driver_log", JSONArray(served.recentLog()))
                    .put("reference", reference?.record?.path ?: JSONObject.NULL).put("steps", stepsJson).toString(1))
            }
            update { it.copy(phase = "failed", error = e.message ?: e.toString(), record = rec) }
        } finally {
            served.close()
        }
        return state
    }

    private suspend fun untilForeground(ok: (String) -> Boolean) {
        withTimeout(10_000) {
            while (true) {
                service.settle(400, 3000)
                val fg = service.readScreen(spec.screen.skipPackages)?.foreground
                if (fg != null && ok(fg)) return@withTimeout
                delay(200)
            }
        }
    }

    private class Reply(val ids: List<Int>, val text: String, val done: Map<String, Double>)

    private suspend fun untilMark(served: BinaryRunner.Served, name: String) {
        for (e in served.events) {
            if (e is RunEvent.Mark && e.name == name) return
            if (e is RunEvent.Exit) throw IllegalStateException("driver exited (${e.code}) before $name")
        }
        throw IllegalStateException("driver closed before $name")
    }

    private suspend fun collect(served: BinaryRunner.Served): Reply {
        val ids = mutableListOf<Int>()
        val text = StringBuilder()
        val utf8 = Utf8Accumulator()
        for (e in served.events) {
            when (e) {
                is RunEvent.Mark -> if (e.name == "decode_start") update { it.copy(phase = "decode") }
                is RunEvent.Step -> {
                    ids += e.ids.toList()
                    text.append(utf8.push(e.bytes))
                    update { it.copy(live = text.toString(), liveTokens = ids.size) }
                }
                is RunEvent.Done -> return Reply(ids, text.append(utf8.flush()).toString(), e.fields)
                is RunEvent.Exit -> throw IllegalStateException("driver exited (${e.code}) during a request")
                else -> {}
            }
        }
        throw IllegalStateException("driver closed during a request")
    }

    private suspend fun warmup(served: BinaryRunner.Served, out: File) {
        val png = File(out, "warmup.png")
        Bitmap.createBitmap(352, 736, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
            .let { b -> png.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val txt = File(out, "warmup.txt").apply { writeText("Describe the screen.<__media__>") }
        served.send(0, txt, png, 4)
        collect(served)
    }

    /**
     * The app window only, cropped to the band between the system bars and scaled like the probe's screenshots. It is
     * taken until two frames in a row are identical: Settings' blurred search bar fades in after launch, and two runs that
     * caught it at different moments sent different images and parted at step 2. Returns the number of frames taken.
     */
    private suspend fun writeScreenshot(windowId: Int, file: File): Int {
        var prev: Bitmap? = null
        for (tries in 1..6) {
            val cur = capture(windowId)
            if (prev != null && cur.sameAs(prev)) {
                file.outputStream().use { cur.compress(Bitmap.CompressFormat.PNG, 100, it) }
                return tries
            }
            prev = cur
            delay(350)   // the platform refuses screenshots closer than ~333 ms apart
        }
        file.outputStream().use { prev!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return 7   // never settled; the last frame is used
    }

    private suspend fun capture(windowId: Int): Bitmap {
        val windowShot = service.screenshot(windowId)
        val shot = windowShot ?: service.screenshotDisplay() ?: throw IllegalStateException("screenshot failed")
        val win = windowShot?.let { service.windows.firstOrNull { it.id == windowId }?.let { w -> Rect().also(w::getBoundsInScreen) } }
            ?: Rect(0, 0, shot.width, shot.height)
        val band = service.band()
        val top = (band.top - win.top).coerceIn(0, shot.height - 1)
        val bottom = (band.bottom - win.top).coerceIn(top + 1, shot.height)
        val cropped = Bitmap.createBitmap(shot, 0, top, shot.width, bottom - top)
        val s = spec.screen.scale
        return Bitmap.createScaledBitmap(cropped, Math.round(cropped.width * s).toInt(), Math.round(cropped.height * s).toInt(), true)
    }

    private fun verify(answer: String?): Boolean {
        val v = task.verify
        v.optJSONArray("answer_any")?.let { any ->
            val flat = (answer ?: "").replace(",", "").lowercase()
            return (0 until any.length()).any { any.getString(it).replace(",", "").lowercase() in flat }
        }
        if (v.has("answer_battery")) {
            val level = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val nums = Regex("\\d+").findAll(answer ?: "").map { it.value.toInt() }
            return nums.any { kotlin.math.abs(it - level) <= v.getInt("answer_battery") }
        }
        v.optJSONArray("setting")?.let { s ->
            if (s.getString(1) == "ui_night_mode") {
                return context.getSystemService(UiModeManager::class.java).nightMode.toString() == s.getString(2)
            }
        }
        return false
    }
}
