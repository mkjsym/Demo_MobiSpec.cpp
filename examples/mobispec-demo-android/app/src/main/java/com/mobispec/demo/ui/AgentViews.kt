package com.mobispec.demo.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobispec.demo.agent.AgentRace
import com.mobispec.demo.agent.AgentStep
import com.mobispec.demo.agent.EpisodeState
import com.mobispec.demo.manifest.Role
import com.mobispec.demo.manifest.Scenario
import java.io.File

private val VISION = Color(0xFF7E57C2)
private val PREFILL = Color(0xFF8D6E63)
private val ACT = Color(0xFF546E7A)

/** Where an episode is `t` ms after its first observation, from the recorded step times. */
data class EpisodeAt(val step: Int, val phase: String, val doneSteps: Int, val tokens: Int, val decodeMs: Double, val calls: Double,
                     val clockMs: Long, val finished: Boolean, val stepMs: List<FloatArray>)

/** Per step: observe, vision, the rest of the first-token wait, decode, act (ms), and how much of it `t` has reached. */
fun EpisodeState.at(t: Long?): EpisodeAt {
    val end = episodeMs ?: steps.lastOrNull()?.endMs ?: 0L
    val tt = (t ?: end).coerceIn(0L, end)
    var start = 0L
    var tokens = 0
    var dms = 0.0
    var calls = 0.0
    val parts = mutableListOf<FloatArray>()
    for ((i, s) in steps.withIndex()) {
        val p = segments(s, s.endMs - start)
        if (tt >= s.endMs) {
            tokens += s.tokens; dms += s.decodeMs; calls += if (s.al > 0) s.tokens / s.al else 0.0
            parts += p; start = s.endMs
            continue
        }
        // the step under way: cut its segments at t
        var left = (tt - start).toFloat()
        parts += FloatArray(p.size) { j -> minOf(p[j], left).coerceAtLeast(0f).also { left -= p[j] } }
        val think = start + s.observeMs + s.prefillMs
        val phase = when {
            tt < start + s.observeMs -> "reading the screen"
            tt < think -> "encoding the screen"
            tt < think + s.decodeMs -> "generating"
            else -> "acting"
        }
        val f = if (s.decodeMs > 0) ((tt - think) / s.decodeMs).coerceIn(0.0, 1.0) else 0.0
        return EpisodeAt(i, phase, i, tokens + (s.tokens * f).toInt(), dms + s.decodeMs * f, calls + f * (if (s.al > 0) s.tokens / s.al else 0.0),
            tt, false, parts)
    }
    return EpisodeAt(steps.size - 1, "done", steps.size, tokens, dms, calls, tt, true, parts)
}

private fun segments(s: AgentStep, span: Long): FloatArray {
    val vision = s.visionMs.toFloat()
    val prefill = (s.prefillMs - s.visionMs).toFloat().coerceAtLeast(0f)
    val decode = s.decodeMs.toFloat()
    val act = (span - s.observeMs - s.prefillMs - s.decodeMs).toFloat().coerceAtLeast(0f)   // the action and the settle after it
    return floatArrayOf(s.observeMs.toFloat(), vision, prefill, decode, act)
}

/**
 * One agent run in the demo's single layout (as [RunView] for text): badge, task clock and its ratio to AR, steps,
 * decode rate, a bar per step split into where its time went, and the screen the model read at that step.
 */
@Composable
fun AgentRunView(e: EpisodeState, r: AgentRace, sc: Scenario?, t: Long?, k: Float, modifier: Modifier = Modifier) {
    val role = sc?.methods?.firstOrNull { it.id == e.methodId }?.role ?: if (e.onReference) Role.SD else Role.AR
    val accent = Palette.accent(role)
    val at = e.at(t)
    val ar = r.episodes.first()
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MethodBadge(e.methodTitle, role, k)
            Spacer(Modifier.weight(1f))
            when {
                e.episodeMs == null -> StatusChip(e.phase.uppercase(), Palette.warn, k)
                !at.finished -> StatusChip(if (t != null) "REPLAY" else "LIVE", Palette.sd, k)
                e.success == true -> StatusChip("DONE", Palette.ok, k)
                else -> StatusChip("FAILED", Palette.bad, k)
            }
        }
        Row(Modifier.padding(top = (10 * k).dp), verticalAlignment = Alignment.Bottom) {
            Text(sc?.model ?: "", color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = (17 * k).sp, maxLines = 1)
            Text("   " + listOfNotNull(sc?.let { backendLabel(it) }, titleDetail(e.methodTitle), "phone agent").joinToString(" - "),
                color = Palette.muted, fontSize = (13 * k).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(r.task.title, color = Palette.text, fontSize = (15 * k).sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.padding(top = (12 * k).dp))
        Text(r.task.goal, color = Palette.muted, fontSize = (13 * k).sp, lineHeight = (18 * k).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height((18 * k).dp))

        // the speed: the task clock, and the finished task's ratio to AR
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmt(at.clockMs / 1000.0, 1), color = accent, fontWeight = FontWeight.Bold, fontSize = (80 * k).sp, lineHeight = (80 * k).sp, style = Numbers)
            Text(" s", color = Palette.muted, fontSize = (20 * k).sp, modifier = Modifier.padding(bottom = (12 * k).dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = (8 * k).dp)) {
                val sd = role == Role.SD
                val x = if (!sd) 1.0 else if (at.finished && ar.episodeMs != null && e.episodeMs != null) ar.episodeMs.toDouble() / e.episodeMs else null
                Text("${fmt(x)}x", color = if (sd) accent else Palette.muted, fontWeight = FontWeight.Bold, fontSize = (34 * k).sp, style = Numbers)
                Text(if (sd) "faster task" else "baseline", color = Palette.muted, fontSize = (12 * k).sp)
            }
        }
        Spacer(Modifier.height((16 * k).dp))
        Row(Modifier.fillMaxWidth()) {
            Metric("STEPS", "${at.doneSteps}", "/ ${e.steps.size}", k, Modifier.weight(1f))
            Metric("DECODE", fmt(if (at.decodeMs > 0) at.tokens / (at.decodeMs / 1000) else null, 1), "tok/s", k, Modifier.weight(1f))
            Metric("TOKENS / CALL", fmt(if (at.calls > 0) at.tokens / at.calls else null), "", k, Modifier.weight(1f))
        }
        Spacer(Modifier.height((14 * k).dp))
        TokenProgress(at.clockMs.toInt(), (e.episodeMs ?: 1L).toInt(), accent, height = (10 * k).dp)
        Spacer(Modifier.height((6 * k).dp))
        Row {
            Text(if (at.finished) "finished in ${fmt((e.episodeMs ?: 0L) / 1000.0, 2)} s" else "step ${at.step + 1}: ${at.phase}",
                color = if (at.finished) Palette.ok else Palette.muted, fontSize = (12 * k).sp, fontWeight = if (at.finished) FontWeight.Bold else FontWeight.Normal,
                style = Numbers, maxLines = 1, modifier = Modifier.weight(1f))
            if (role == Role.SD && e.steps.isNotEmpty() && e.steps.all { it.same == true }) {
                Text("same tokens as AR, every step", color = Palette.ok, fontSize = (12 * k).sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height((18 * k).dp))

        // the process: one bar per step, split into where its time went
        Row(verticalAlignment = Alignment.Bottom) {
            Text("TIME PER STEP", color = Palette.muted, fontSize = (11 * k).sp, fontWeight = FontWeight.Bold, letterSpacing = (1 * k).sp,
                maxLines = 1, modifier = Modifier.weight(1f))
            Text("${e.steps.size} steps", color = Palette.muted, fontSize = (12 * k).sp, style = Numbers, maxLines = 1)
        }
        Spacer(Modifier.height((6 * k).dp))
        val scale = remember(r) { r.episodes.maxOf { ep -> ep.at(null).stepMs.maxOfOrNull { it.sum() } ?: 1f }.coerceAtLeast(1f) }
        StepTimes(at.stepMs, e.steps.size, scale, accent, Modifier.fillMaxWidth().height((88 * k).dp))
        Spacer(Modifier.height((6 * k).dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((6 * k).dp)) {
            for ((label, c) in listOf("vision" to VISION, "prefill" to PREFILL, "decode" to accent, "observe, act" to ACT)) {
                Swatch(c, k); Text(label, color = Palette.muted, fontSize = (11 * k).sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height((14 * k).dp))
        HorizontalDivider(color = Palette.line)
        Spacer(Modifier.height((12 * k).dp))
        // the screen the model read at this step, and what it did
        val step = e.steps.getOrNull(at.step)
        Text(step?.let { "${it.n}. ${it.action}" } ?: "", color = Palette.text, fontSize = (13 * k).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height((6 * k).dp))
        val dir = e.record?.parentFile
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            if (dir != null && step != null) StepScreen(File(dir, "step%02d.png".format(step.n)))
        }
    }
}

@Composable
private fun StepScreen(f: File) {
    val bmp = remember(f.path) { runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() } ?: return
    Image(bmp, contentDescription = "screen", contentScale = ContentScale.Fit,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, Palette.line, RoundedCornerShape(6.dp)))
}

/** A column per step, oldest on the left: observe, vision, prefill, decode and act stacked from the bottom. */
@Composable
private fun StepTimes(parts: List<FloatArray>, steps: Int, scale: Float, accent: Color, modifier: Modifier) {
    val colors = listOf(ACT, VISION, PREFILL, accent, ACT)
    Canvas(modifier) {
        val slot = size.width / steps.coerceAtLeast(1)
        val bw = slot * 0.7f
        drawLine(Palette.line, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        parts.forEachIndexed { i, p ->
            var y = size.height
            for (j in p.indices) {
                val h = p[j] / scale * size.height
                if (h <= 0f) continue
                drawRect(colors[j], Offset(i * slot + (slot - bw) / 2, y - h), Size(bw, h))
                y -= h
            }
        }
    }
}
