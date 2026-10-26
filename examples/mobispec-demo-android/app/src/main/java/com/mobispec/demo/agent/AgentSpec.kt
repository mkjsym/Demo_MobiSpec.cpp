package com.mobispec.demo.agent

import com.mobispec.demo.manifest.objects
import com.mobispec.demo.manifest.strings
import org.json.JSONObject

// A port of tools/agent_spec.py, whose strings the manifest carries: for the same screen the app has to send the
// prompt bytes the host probe measured (the unit test checks both against agent-prompts.json).

data class ScreenConfig(
    val scale: Double,
    val crop: String,             // "system_bars": elements and screenshot keep only the band between the bars
    val imageMaxTokens: Int,
    val nPredict: Int,
    val skipPackages: Set<String>,
)

data class AgentTask(
    val id: String,
    val title: String,
    val goal: String,
    val maxSteps: Int,
    val stop: List<String>,       // packages whose task stack is reset before the episode
    val verify: JSONObject,
    val restore: List<String>,    // shell commands (adb side) that put the start state back
)

/** One on-screen element, with the fields the prompt prints and the bounds an action needs. */
data class Element(
    val text: String?,
    val desc: String?,
    val cls: String?,
    val pkg: String?,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0,
)

/** A parsed action: its JSON fields in the reply's own types, as the history line prints them. */
class AgentAction(val fields: Map<String, Any?>) {
    val type: String get() = fields["action_type"] as String
    val index: Int? get() = (fields["index"] as? Number)?.toInt()
    val text: String? get() = fields["text"]?.toString()
    val appName: String? get() = fields["app_name"] as? String
    val direction: String? get() = fields["direction"] as? String
    val goalStatus: String? get() = fields["goal_status"] as? String
}

class AgentSpec(
    private val template: String,
    private val menu: String,
    private val guidelines: String,
    val screen: ScreenConfig,
    val tasks: List<AgentTask>,
    private val appLabels: Map<String, String> = emptyMap(),   // the foreground line names the app as the phone shows it
) {
    fun label(pkg: String): String = appLabels[pkg] ?: pkg

    fun prompt(goal: String, foreground: String, elements: List<Element>, history: List<String>): String {
        val lines = history.mapIndexed { i, h -> "Step ${i + 1}: $h" }.joinToString("\n").ifEmpty { "(none yet)" }
        return format(template, mapOf(
            "menu" to menu, "foreground" to (appLabels[foreground]?.let { "$it ($foreground)" } ?: foreground),
            "goal" to goal, "history" to lines,
            "elements" to elements.mapIndexed(::elementLine).joinToString("\n"), "guidelines" to guidelines,
        ))
    }

    companion object {
        private val KEYS = listOf("action_type", "index", "text", "app_name", "direction", "goal_status")
        // names the model writes for an action it means unambiguously (lowercased, spaces and hyphens as underscores)
        private val ACTION_ALIASES = mapOf("go_back" to "navigate_back", "back" to "navigate_back", "go_home" to "navigate_home",
            "home" to "navigate_home", "go_to_home_screen" to "navigate_home", "go_to_the_home_screen" to "navigate_home", "tap" to "click")

        private fun actionType(name: Any?): String {
            val key = (name as? String)?.trim()?.lowercase()?.replace(' ', '_')?.replace('-', '_') ?: ""
            return ACTION_ALIASES[key] ?: key
        }

        private val REQUIRED = mapOf(
            "click" to listOf("index"), "long_press" to listOf("index"), "input_text" to listOf("text", "index"),
            "scroll" to listOf("direction"), "open_app" to listOf("app_name"), "status" to listOf("goal_status"),
            "answer" to listOf("text"), "keyboard_enter" to emptyList(), "navigate_home" to emptyList(),
            "navigate_back" to emptyList(), "wait" to emptyList(),
        )

        fun parse(agent: JSONObject, tasks: List<JSONObject>): AgentSpec {
            val s = agent.getJSONObject("screen")
            return AgentSpec(
                agent.getString("template"), agent.getString("menu"), agent.getString("guidelines"),
                ScreenConfig(s.getDouble("scale"), s.getString("crop"), s.getInt("image_max_tokens"),
                    s.getInt("n_predict"), s.getJSONArray("skip_packages").strings().toSet()),
                tasks.map {
                    val setup = it.optJSONObject("setup") ?: JSONObject()
                    AgentTask(it.getString("id"), it.optString("title", it.getString("id")), it.getString("goal"),
                        it.getInt("max_steps"), setup.optJSONArray("stop")?.strings() ?: emptyList(),
                        it.getJSONObject("verify"), it.optJSONArray("restore")?.strings() ?: emptyList())
                },
                agent.optJSONObject("app_labels")?.let { l -> l.keys().asSequence().associateWith { l.getString(it) } } ?: emptyMap(),
            )
        }

        fun parseTasks(o: JSONObject): List<JSONObject> = o.optJSONArray("tasks")?.objects() ?: emptyList()

        /** Python str.format with named fields only: {name} is replaced, {{ and }} are literal braces. */
        fun format(template: String, values: Map<String, String>): String {
            val out = StringBuilder()
            var i = 0
            while (i < template.length) {
                val c = template[i]
                if ((c == '{' || c == '}') && i + 1 < template.length && template[i + 1] == c) {
                    out.append(c); i += 2; continue
                }
                if (c == '{') {
                    val end = template.indexOf('}', i)
                    require(end > i) { "unclosed field at $i" }
                    out.append(values[template.substring(i + 1, end)] ?: error("no value for ${template.substring(i, end + 1)}"))
                    i = end + 1; continue
                }
                out.append(c); i++
            }
            return out.toString()
        }

        fun quote(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

        fun elementLine(i: Int, e: Element): String {
            val bits = mutableListOf<String>()
            if (!e.text.isNullOrEmpty()) bits += "text=" + quote(e.text)
            if (!e.desc.isNullOrEmpty()) bits += "desc=" + quote(e.desc)
            if (!e.cls.isNullOrEmpty()) bits += "class=" + e.cls.substringAfterLast('.')
            val flags = listOf("clickable" to e.clickable, "editable" to e.editable, "scrollable" to e.scrollable,
                "checked" to e.checked, "selected" to e.selected).filter { it.second }.map { it.first }
            if (flags.isNotEmpty()) bits += flags.joinToString("|")
            return "UI element $i: " + bits.joinToString(", ")
        }

        fun summarise(a: AgentAction?): String {
            if (a == null) return "no action (parse failure)"
            val args = listOf("index", "text", "app_name", "direction", "goal_status").mapNotNull { k ->
                when (val v = a.fields[k]) {
                    null, "" -> null
                    is String -> "$k=${quote(v)}"
                    else -> "$k=$v"
                }
            }
            return if (args.isEmpty()) a.type else "${a.type}(${args.joinToString(", ")})"
        }

        /** (reason, action or null): the first balanced {...} that parses as an object, with its required fields. */
        fun parseReply(text: String): Pair<String, AgentAction?> {
            var depth = 0
            var start = -1
            for ((i, c) in text.withIndex()) {
                if (c == '{') {
                    if (depth == 0) start = i
                    depth++
                } else if (c == '}' && depth > 0) {
                    depth--
                    if (depth == 0) {
                        val o = runCatching { JSONObject(text.substring(start, i + 1)) }.getOrNull() ?: continue
                        val reason = if (o.has("reason") && !o.isNull("reason")) o.get("reason").toString() else ""
                        return reason to valid(o)
                    }
                }
            }
            return "" to null
        }

        private fun valid(o: JSONObject): AgentAction? {
            val kind = actionType(o.opt("action_type"))
            val need = REQUIRED[kind] ?: return null
            if (need.any { !o.has(it) || o.isNull(it) || o.get(it) == "" }) return null
            if ("index" in need && o.get("index") !is Int && o.get("index") !is Long) return null
            return AgentAction(KEYS.filter { o.has(it) }.associateWith { if (it == "action_type") kind else if (o.isNull(it)) null else o.get(it) })
        }
    }
}
