package com.mobispec.demo

import com.mobispec.demo.manifest.DemoManifest
import com.mobispec.demo.manifest.Headline
import com.mobispec.demo.manifest.Placeholders
import com.mobispec.demo.manifest.Role
import com.mobispec.demo.run.EventParser
import com.mobispec.demo.run.Lossless
import com.mobispec.demo.run.Origin
import com.mobispec.demo.run.RunBuilder
import com.mobispec.demo.run.RunEvent
import com.mobispec.demo.run.RunRecord
import com.mobispec.demo.run.Utf8Accumulator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunPipelineTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    // Events written by the host build of llama-speculative-eagle-mast (Qwen3-4B, EAGLE-3 head,
    // Korean prompt under the chat template, MOBISPEC_EVENTS_TREE=1).
    private fun mastEvents() = resource("mast-ko.jsonl").lines().mapNotNull(EventParser::parse)

    private fun build(events: List<RunEvent>): RunRecord {
        val b = RunBuilder("s", "mast", "MAST", "p", Role.SD, "gpu", listOf("bin"))
        events.forEach { b.accept(it) }
        b.accept(RunEvent.Exit(0, 1234))
        return b.build(emptyMap())
    }

    @Test fun parsesEveryEventKind() {
        val ev = mastEvents()
        assertTrue(ev.first() is RunEvent.Start)
        assertEquals(listOf("loaded", "decode_start"), ev.filterIsInstance<RunEvent.Mark>().map { it.name })
        val done = ev.last() as RunEvent.Done
        assertEquals(41.0, done.fields["tokens"]!!, 0.0)
        assertEquals(1.333, done.fields["al"]!!, 1e-3)
    }

    @Test fun textMatchesDriverBytesAcrossSplitCharacters() {
        val rec = build(mastEvents())
        assertEquals(resource("mast-ko.expected.txt"), rec.text)
        assertTrue(rec.ok)
    }

    @Test fun lastTokenOfAStepIsTheTargets() {
        val rec = build(mastEvents())
        val multi = rec.steps.first { it.ids.size > 1 }
        assertEquals(Origin.DRAFT, multi.origin(0, speculative = true))
        assertEquals(Origin.TARGET, multi.origin(multi.ids.size - 1, speculative = true))
        assertEquals(Origin.TARGET, multi.origin(0, speculative = false))
    }

    @Test fun treePathIndicesAreInRange() {
        for (s in build(mastEvents()).steps) {
            val tree = s.tree ?: continue
            for (i in s.path ?: IntArray(0)) assertTrue(i in tree.indices)
        }
    }

    @Test fun recordSurvivesJsonRoundTrip() {
        val rec = build(mastEvents())
        val back = RunRecord.fromJson(JSONObject(rec.toJson().toString()))
        assertEquals(rec.text, back.text)
        assertEquals(rec.ids, back.ids)
        assertEquals(rec.done, back.done)
        assertEquals(rec.marks, back.marks)
        assertEquals(rec.steps.mapNotNull { it.tree?.size }, back.steps.mapNotNull { it.tree?.size })
    }

    @Test fun derivedTimesUseTheLoadedMark() {
        val rec = build(mastEvents())
        val loaded = rec.marks["loaded"]!!
        assertEquals((rec.steps.first().tUs - loaded) / 1000.0, rec.ttftMs!!, 1e-9)
        assertNotNull(rec.e2eTps)
        assertNotNull(rec.generationTps)
        assertEquals(rec.decodeTps, rec.headlineTps(Headline.DECODE))
    }

    @Test fun utf8CharacterSplitOverPushes() {
        val bytes = "\uAC00\uB098\uB2E4 ok".toByteArray(Charsets.UTF_8)
        val acc = Utf8Accumulator()
        val out = StringBuilder()
        for (b in bytes) out.append(acc.push(byteArrayOf(b)))
        out.append(acc.flush())
        assertEquals("\uAC00\uB098\uB2E4 ok", out.toString())
        assertEquals("", Utf8Accumulator().push(bytes.copyOfRange(0, 2)))
    }

    @Test fun losslessComparesTheCommonPrefix() {
        assertTrue(Lossless.of(listOf(1, 2, 3), listOf(1, 2, 3, 4)).identical)
        val d = Lossless.of(listOf(1, 2, 3), listOf(1, 9, 3))
        assertFalse(d.identical)
        assertEquals(1, d.firstDiff)
        assertFalse(Lossless.of(emptyList(), listOf(1)).identical)
    }

    @Test fun generatedManifestParses() {
        val m = DemoManifest.parse(resource("demo-manifest.json"))
        assertEquals(listOf("gpu-vicuna-7b", "gpu-llama3-8b", "npu-qwen3-4b", "npu-qwen3vl-4b"), m.scenarios.map { it.id })
        val vlm = m.scenarios.last()
        assertEquals(Headline.GENERATION, vlm.headline)
        val ours = vlm.sdMethods.first { it.id == "ours" }
        assertEquals("ar-deep", vlm.ar(ours.target)!!.id)
        assertEquals("ar", vlm.ar("a32")!!.id)
        // the fixture is tools/gen_manifest.py output with the reference numbers removed
        assertTrue(vlm.prompts.all { it.fixture != null && it.image != null })
        val llm = m.scenarios.first { it.id == "npu-qwen3-4b" }
        assertEquals("ref-a32", llm.reference("a32")!!.id)
        val gpu = m.scenarios.first()
        assertEquals("ar", gpu.reference("gpu")!!.id)
        assertNull(gpu.modelsRoot)
        assertNotNull(gpu.losslessNote)
        assertNull(llm.losslessNote)
    }

    @Test fun placeholdersResolve() {
        val v = mapOf("MODELS" to "/m", "N" to "128")
        assertEquals("/m/gpu/x.gguf", Placeholders.resolve("\${MODELS}/gpu/x.gguf", v))
        assertEquals("128", Placeholders.resolve("\${N}", v))
    }
}
