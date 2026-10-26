package com.mobispec.demo

import com.mobispec.demo.agent.AgentSpec
import com.mobispec.demo.agent.Element
import com.mobispec.demo.manifest.objects
import com.mobispec.demo.manifest.strings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// agent-prompts.json is written by tools/agent_spec.py (python3 tools/agent_spec.py): the prompts and parses the
// host probe produced, which the app has to reproduce byte for byte.
class AgentSpecTest {
    private val fixture = JSONObject(javaClass.classLoader!!.getResource("agent-prompts.json")!!.readText())
    private val spec = AgentSpec.parse(fixture.getJSONObject("agent"), AgentSpec.parseTasks(fixture))

    private fun element(o: JSONObject) = Element(
        text = o.optString("text").ifEmpty { null }, desc = if (o.isNull("desc")) null else o.optString("desc").ifEmpty { null },
        cls = if (o.isNull("cls")) null else o.optString("cls").ifEmpty { null }, pkg = o.optString("pkg").ifEmpty { null },
        clickable = o.optBoolean("clickable"), editable = o.optBoolean("editable"), scrollable = o.optBoolean("scrollable"),
        checked = o.optBoolean("checked"), selected = o.optBoolean("selected"),
    )

    @Test fun promptsMatchTheProbe() {
        for (c in fixture.getJSONArray("cases").objects()) {
            val got = spec.prompt(c.getString("goal"), c.getString("foreground"),
                c.getJSONArray("elements").objects().map(::element), c.getJSONArray("history").strings())
            assertEquals(c.getString("prompt"), got)
        }
    }

    @Test fun repliesParseLikeTheProbe() {
        for (r in fixture.getJSONArray("replies").objects()) {
            val (reason, action) = AgentSpec.parseReply(r.getString("text"))
            assertEquals(r.getString("text"), r.getString("reason"), reason)
            assertEquals(r.getString("text"), r.getString("summary"), AgentSpec.summarise(action))
            if (r.isNull("action")) assertNull(action)
        }
    }

    @Test fun tasksAndScreen() {
        assertEquals(fixture.getJSONArray("tasks").length(), spec.tasks.size)
        assertEquals(256, spec.screen.imageMaxTokens)
        assertEquals("{a} {", AgentSpec.format("{{a}} {{", emptyMap()))
    }
}
