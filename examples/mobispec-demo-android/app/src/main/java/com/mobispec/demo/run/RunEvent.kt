package com.mobispec.demo.run

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** One line of the MOBISPEC_EVENTS stream (common/mobispec-events.h), or a process-level event. */
sealed interface RunEvent {
    data class Start(val bin: String) : RunEvent
    data class Mark(val name: String, val tUs: Long) : RunEvent
    class Step(
        val tUs: Long,
        val ids: IntArray,
        val lens: IntArray,          // byte length of each token's piece inside bytes
        val bytes: ByteArray,
        val tree: List<IntArray>?,   // [parent index, token] per verified node, in batch order
        val path: IntArray?,         // accepted node indices, root first
    ) : RunEvent
    data class Done(val tUs: Long, val fields: Map<String, Double>) : RunEvent
    data class Log(val stderr: Boolean, val line: String) : RunEvent
    data class Exit(val code: Int, val wallMs: Long) : RunEvent
    data class Failed(val message: String) : RunEvent
}

object EventParser {
    fun parse(line: String): RunEvent? {
        if (line.isBlank()) return null
        val o = try { JSONObject(line) } catch (_: Exception) { return null }
        val t = o.optLong("t_us")
        return when (val ev = o.optString("ev")) {
            "start" -> RunEvent.Start(o.optString("bin"))
            "step" -> {
                val ids = o.getJSONArray("ids").let { a -> IntArray(a.length()) { a.getInt(it) } }
                val bytes = Base64.getDecoder().decode(o.optString("b64"))
                val lens = o.optJSONArray("lens")?.let { a -> IntArray(a.length()) { a.getInt(it) } }
                    ?: IntArray(ids.size).also { if (it.isNotEmpty()) it[it.size - 1] = bytes.size }
                val tree = o.optJSONArray("tree")?.let { a ->
                    (0 until a.length()).map { i -> a.getJSONArray(i).let { intArrayOf(it.getInt(0), it.getInt(1)) } }
                }
                val path = o.optJSONArray("path")?.let { a -> IntArray(a.length()) { a.getInt(it) } }
                RunEvent.Step(t, ids, lens, bytes, tree, path)
            }
            "done" -> RunEvent.Done(t, buildMap {
                for (k in o.keys()) if (k != "ev" && k != "t_us") put(k, o.getDouble(k))
            })
            "" -> null
            else -> RunEvent.Mark(ev, t)
        }
    }
}

/** Joins token bytes into text; a character split across two steps is held back until complete. */
class Utf8Accumulator {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)

    fun push(bytes: ByteArray): String {
        val all = pending + bytes
        val keep = incompleteTail(all)
        val ready = all.copyOfRange(0, all.size - keep)
        pending = all.copyOfRange(all.size - keep, all.size)
        if (ready.isEmpty()) return ""
        val out = CharBuffer.allocate(ready.size * 2)
        decoder.reset()
        decoder.decode(ByteBuffer.wrap(ready), out, true)
        decoder.flush(out)
        out.flip()
        return out.toString()
    }

    fun flush(): String {
        if (pending.isEmpty()) return ""
        val s = String(pending, Charsets.UTF_8)
        pending = ByteArray(0)
        return s
    }

    // Number of trailing bytes that start a multi-byte sequence the buffer does not finish yet.
    private fun incompleteTail(b: ByteArray): Int {
        for (back in 1..minOf(3, b.size)) {
            val c = b[b.size - back].toInt() and 0xFF
            if (c and 0xC0 == 0x80) continue          // continuation byte, keep looking back
            val need = when {
                c and 0xE0 == 0xC0 -> 2
                c and 0xF0 == 0xE0 -> 3
                c and 0xF8 == 0xF0 -> 4
                else -> 1
            }
            return if (need > back) back else 0
        }
        return 0
    }
}
