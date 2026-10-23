package com.mobispec.demo.run

import com.mobispec.demo.manifest.Role
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Origin { TARGET, DRAFT }

/** Tokens one verify step (or one AR step) made available, with their text already UTF-8 joined. */
class StepRec(
    val tUs: Long,
    val ids: IntArray,
    val texts: List<String>,
    val tree: List<IntArray>?,
    val path: IntArray?,
) {
    // Every step ends with the target's own token; what precedes it was proposed by the drafter
    // and accepted unchanged.
    fun origin(i: Int, speculative: Boolean): Origin =
        if (speculative && i < ids.size - 1) Origin.DRAFT else Origin.TARGET
}

class RunRecord(
    val scenarioId: String,
    val methodId: String,
    val methodTitle: String,
    val promptId: String,
    val role: Role,
    val target: String,
    val startedAtMs: Long,
    val bin: String,
    val command: List<String>,
    val marks: Map<String, Long>,
    val steps: List<StepRec>,
    val done: Map<String, Double>,
    val exitCode: Int,
    val wallMs: Long,
    val device: Map<String, Double>,
    val logTail: List<String>,
) {
    val speculative get() = role == Role.SD
    val ok get() = exitCode == 0 && done.isNotEmpty()
    val ids: List<Int> get() = steps.flatMap { it.ids.asList() }
    val text: String get() = steps.joinToString("") { it.texts.joinToString("") }
    val tokens: Int get() = done["tokens"]?.toInt() ?: ids.size

    /** Decode tokens/s exactly as the binary reports it (the published definition per driver). */
    val decodeTps: Double? get() = done["tps"]
    /** Tokens after the first over first token -> last token (drafter prefill and setup included). */
    val generationTps: Double? get() {
        if (steps.size < 2) return null
        val dt = steps.last().tUs - steps.first().tUs
        return if (dt > 0) (steps.sumOf { it.ids.size } - steps.first().ids.size) / (dt / 1e6) else null
    }
    fun headlineTps(h: com.mobispec.demo.manifest.Headline): Double? =
        if (h == com.mobispec.demo.manifest.Headline.GENERATION) generationTps else decodeTps
    val al: Double? get() = done["al"]

    val loadMs: Double? get() = marks["loaded"]?.let { it / 1000.0 }
    /** Prompt submitted -> first token visible (prefill, plus first verify for the EAGLE drivers). */
    val ttftMs: Double? get() {
        val loaded = marks["loaded"] ?: return null
        val first = steps.firstOrNull()?.tUs ?: return null
        return (first - loaded) / 1000.0
    }
    /** All delivered tokens over prompt-submitted -> last token, i.e. what a user waits through. */
    val e2eTps: Double? get() {
        val loaded = marks["loaded"] ?: return null
        val last = steps.lastOrNull()?.tUs ?: return null
        val n = steps.sumOf { it.ids.size }
        return if (last > loaded) n / ((last - loaded) / 1e6) else null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("scenario", scenarioId); put("method", methodId); put("method_title", methodTitle)
        put("prompt", promptId); put("role", role.name); put("target", target)
        put("started_at_ms", startedAtMs); put("bin", bin); put("command", JSONArray(command))
        put("marks", JSONObject(marks as Map<*, *>))
        put("steps", JSONArray().apply {
            for (s in steps) put(JSONObject().apply {
                put("t_us", s.tUs)
                put("ids", JSONArray(s.ids.toList()))
                put("texts", JSONArray(s.texts))
                s.tree?.let { t -> put("tree", JSONArray(t.map { JSONArray(it.toList()) })) }
                s.path?.let { put("path", JSONArray(it.toList())) }
            })
        })
        put("done", finite(done))
        put("exit_code", exitCode); put("wall_ms", wallMs)
        put("device", finite(device))
        put("log_tail", JSONArray(logTail))
    }

    companion object {
        // org.json cannot write NaN (toString() returns null); an unknown value is left out instead
        private fun finite(m: Map<String, Double>) = JSONObject(m.filterValues { it.isFinite() } as Map<*, *>)

        fun fromJson(o: JSONObject): RunRecord {
            val marks = o.getJSONObject("marks").let { m -> m.keys().asSequence().associateWith { m.getLong(it) } }
            val done = o.getJSONObject("done").let { m -> m.keys().asSequence().associateWith { m.getDouble(it) } }
            val device = o.optJSONObject("device")?.let { m -> m.keys().asSequence().associateWith { m.getDouble(it) } } ?: emptyMap()
            val steps = o.getJSONArray("steps").let { a ->
                (0 until a.length()).map { i ->
                    val s = a.getJSONObject(i)
                    StepRec(
                        tUs = s.getLong("t_us"),
                        ids = s.getJSONArray("ids").let { x -> IntArray(x.length()) { x.getInt(it) } },
                        texts = s.getJSONArray("texts").let { x -> (0 until x.length()).map { x.getString(it) } },
                        tree = s.optJSONArray("tree")?.let { x ->
                            (0 until x.length()).map { j -> x.getJSONArray(j).let { intArrayOf(it.getInt(0), it.getInt(1)) } }
                        },
                        path = s.optJSONArray("path")?.let { x -> IntArray(x.length()) { x.getInt(it) } },
                    )
                }
            }
            return RunRecord(
                scenarioId = o.getString("scenario"), methodId = o.getString("method"),
                methodTitle = o.optString("method_title", o.getString("method")), promptId = o.getString("prompt"),
                role = Role.valueOf(o.getString("role")), target = o.optString("target"),
                startedAtMs = o.getLong("started_at_ms"), bin = o.optString("bin"),
                command = o.optJSONArray("command")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                marks = marks, steps = steps, done = done, exitCode = o.getInt("exit_code"), wallMs = o.optLong("wall_ms"),
                device = device,
                logTail = o.optJSONArray("log_tail")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            )
        }
    }
}

/** Folds a live event stream into a RunRecord; the UI reads its snapshot after every event. */
class RunBuilder(
    private val scenarioId: String,
    private val methodId: String,
    private val methodTitle: String,
    private val promptId: String,
    private val role: Role,
    private val target: String,
    private val command: List<String>,
) {
    private val utf8 = Utf8Accumulator()
    private val startedAtMs = System.currentTimeMillis()
    val marks = LinkedHashMap<String, Long>()
    val steps = ArrayList<StepRec>()
    var done: Map<String, Double> = emptyMap(); private set
    var exitCode = -1; private set
    var wallMs = 0L; private set
    var failure: String? = null; private set
    var bin = ""; private set
    private val log = ArrayDeque<String>()

    /** Returns true when the event changed what the UI shows. */
    fun accept(ev: RunEvent): Boolean = when (ev) {
        is RunEvent.Start -> { bin = ev.bin; false }
        is RunEvent.Mark -> { marks[ev.name] = ev.tUs; true }
        is RunEvent.Step -> {
            var off = 0
            val texts = ev.lens.map { len ->
                val end = (off + len).coerceAtMost(ev.bytes.size)
                val s = utf8.push(ev.bytes.copyOfRange(off, end)); off = end; s
            }
            steps += StepRec(ev.tUs, ev.ids, texts, ev.tree, ev.path); true
        }
        is RunEvent.Done -> { done = ev.fields; true }
        is RunEvent.Log -> {
            log.addLast((if (ev.stderr) "E " else "O ") + ev.line)
            while (log.size > LOG_TAIL) log.removeFirst()
            false
        }
        is RunEvent.Exit -> { exitCode = ev.code; wallMs = ev.wallMs; true }
        is RunEvent.Failed -> { failure = ev.message; exitCode = -2; true }
    }

    fun logTail(): List<String> = log.toList()

    fun build(device: Map<String, Double>): RunRecord = RunRecord(
        scenarioId, methodId, methodTitle, promptId, role, target, startedAtMs, bin, command,
        LinkedHashMap(marks), ArrayList(steps), done, exitCode, wallMs, device, logTail(),
    )

    companion object { const val LOG_TAIL = 400 }
}

data class Lossless(val compared: Int, val firstDiff: Int?, val refLen: Int, val runLen: Int) {
    val identical get() = firstDiff == null && compared > 0

    companion object {
        // A speculative run may stop a token or two past the AR run (it commits several tokens per
        // step and the -n budget lands mid-step), so only the common prefix is compared.
        fun of(ref: List<Int>, run: List<Int>): Lossless {
            val k = minOf(ref.size, run.size)
            val diff = (0 until k).firstOrNull { ref[it] != run[it] }
            return Lossless(k, diff, ref.size, run.size)
        }
    }
}

class RunStore(private val root: File) {
    private fun dir(scenario: String, prompt: String, method: String) =
        File(root, "${safe(scenario)}/${safe(prompt)}/${safe(method)}")

    fun save(r: RunRecord): File {
        val d = dir(r.scenarioId, r.promptId, r.methodId).apply { mkdirs() }
        val f = File(d, "${r.startedAtMs}.json")
        val tmp = File(d, "${r.startedAtMs}.json.tmp")
        tmp.writeText(r.toJson().toString())
        tmp.renameTo(f)
        return f
    }

    fun latest(scenario: String, prompt: String, method: String): RunRecord? =
        dir(scenario, prompt, method).listFiles { f -> f.name.endsWith(".json") }
            ?.maxByOrNull { it.name.removeSuffix(".json").toLongOrNull() ?: 0L }
            ?.let { runCatching { RunRecord.fromJson(JSONObject(it.readText())) }.getOrNull() }
            ?.takeIf { it.ok }

    fun clear(scenario: String) { File(root, safe(scenario)).deleteRecursively() }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
