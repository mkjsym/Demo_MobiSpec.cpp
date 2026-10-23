package com.mobispec.demo.manifest

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// demo-manifest.json is generated on the host by tools/gen_manifest.py from the same settings the
// device sweeps use, so a method here runs with exactly the arguments of its published number.

enum class Backend { GPU, NPU }
enum class Kind { LLM, VLM }

// ar: the speed baseline (xAR denominator). ref: the same-graph target run the lossless check
// compares against; when a scenario has no ref, its ar run serves as the reference.
enum class Role { AR, REF, SD }

// decode: tokens/s as the driver reports it (the LLM tables). generation: first token to last token,
// which also carries drafter prefill and register setup (the VLM tables; Full pays its cost there).
enum class Headline { DECODE, GENERATION }

data class PageRef(val tps: Double?, val generationTps: Double?, val al: Double?)

data class Reference(val tps: Double?, val al: Double?, val xar: Double?, val note: String)

data class FileReq(val path: String, val bytes: Long?)

data class Method(
    val id: String,
    val title: String,
    val role: Role,
    val target: String,
    val bin: String,
    val args: List<String>,
    val env: Map<String, String>,
    val affinity: String?,
    val files: List<FileReq>,
    val reference: Reference?,
    val blurb: String,
)

data class Prompt(
    val id: String,
    val title: String,
    val text: String,
    val category: String,
    val fixture: String?,   // VLM: precomputed vision prefix under the models root
    val image: String?,     // VLM: page image shown next to the answer (and passed as --image)
    val refs: Map<String, PageRef> = emptyMap(),   // per-method numbers measured on this exact input
)

data class Scenario(
    val id: String,
    val title: String,
    val subtitle: String,
    val backend: Backend,
    val drafter: Backend,
    val kind: Kind,
    val nPredict: Int,
    val headline: Headline,
    val modelsRoot: String?,     // absolute override, e.g. when the app may read the sweep directories
    val promptFormat: String?,   // "{prompt}" is replaced; null sends the prompt as is
    val allowCustomPrompt: Boolean,
    val losslessNote: String?,   // why the speed baseline's text may part from the SD text (shown next to the badge)
    val prompts: List<Prompt>,
    val methods: List<Method>,
) {
    fun ar(target: String? = null): Method? =
        methods.firstOrNull { it.role == Role.AR && (target == null || it.target == target) }
            ?: methods.firstOrNull { it.role == Role.AR }

    fun reference(target: String): Method? =
        methods.firstOrNull { it.role == Role.REF && it.target == target }
            ?: methods.firstOrNull { it.role == Role.AR && it.target == target }

    val sdMethods: List<Method> get() = methods.filter { it.role == Role.SD }
}

data class DemoManifest(val version: Int, val generated: String, val scenarios: List<Scenario>) {
    companion object {
        fun load(file: File): DemoManifest = parse(file.readText())

        fun parse(text: String): DemoManifest {
            val root = JSONObject(text)
            return DemoManifest(
                version = root.getInt("version"),
                generated = root.optString("generated"),
                scenarios = root.getJSONArray("scenarios").objects().map(::scenario),
            )
        }

        private fun scenario(o: JSONObject) = Scenario(
            id = o.getString("id"),
            title = o.getString("title"),
            subtitle = o.optString("subtitle"),
            backend = Backend.valueOf(o.getString("backend")),
            drafter = Backend.valueOf(o.optString("drafter", o.getString("backend"))),
            kind = Kind.valueOf(o.getString("kind")),
            nPredict = o.optInt("n_predict", 128),
            headline = Headline.valueOf(o.optString("headline", "decode").uppercase()),
            modelsRoot = o.optStringOrNull("models_root"),
            promptFormat = o.optStringOrNull("prompt_format"),
            allowCustomPrompt = o.optBoolean("custom_prompt", true),
            losslessNote = o.optString("lossless_note").ifEmpty { null },
            prompts = o.getJSONArray("prompts").objects().map {
                Prompt(
                    id = it.getString("id"),
                    title = it.optString("title", it.getString("id")),
                    text = it.getString("text"),
                    category = it.optString("category"),
                    fixture = it.optStringOrNull("fixture"),
                    image = it.optStringOrNull("image"),
                    refs = it.optJSONObject("refs")?.let { r ->
                        r.keys().asSequence().associateWith { k ->
                            r.getJSONObject(k).let { x -> PageRef(x.optDoubleOrNull("tps"), x.optDoubleOrNull("generation_tps"), x.optDoubleOrNull("al")) }
                        }
                    } ?: emptyMap(),
                )
            },
            methods = o.getJSONArray("methods").objects().map(::method),
        )

        private fun method(o: JSONObject): Method {
            val env = o.optJSONObject("env")
            return Method(
                id = o.getString("id"),
                title = o.getString("title"),
                role = Role.valueOf(o.getString("role").uppercase()),
                target = o.optString("target", "default"),
                bin = o.getString("bin"),
                args = o.getJSONArray("args").strings(),
                env = env?.keys()?.asSequence()?.associateWith { env.getString(it) } ?: emptyMap(),
                affinity = o.optStringOrNull("affinity"),
                files = o.optJSONArray("files")?.objects()?.map {
                    FileReq(it.getString("path"), if (it.has("bytes")) it.getLong("bytes") else null)
                } ?: emptyList(),
                reference = o.optJSONObject("reference")?.let {
                    Reference(it.optDoubleOrNull("tps"), it.optDoubleOrNull("al"), it.optDoubleOrNull("xar"), it.optString("note"))
                },
                blurb = o.optString("blurb"),
            )
        }
    }
}

/** Placeholders the generator writes into args and env, resolved per run on the device. */
object Placeholders {
    fun resolve(s: String, values: Map<String, String>): String {
        var out = s
        for ((k, v) in values) out = out.replace("\${$k}", v)
        return out
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
internal fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
internal fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
