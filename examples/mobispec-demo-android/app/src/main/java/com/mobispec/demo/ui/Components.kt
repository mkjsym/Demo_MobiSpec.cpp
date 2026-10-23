package com.mobispec.demo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobispec.demo.manifest.Role
import com.mobispec.demo.run.Origin
import com.mobispec.demo.run.StepRec

object Palette {
    val ar = Color(0xFF9AA4B2)
    val sd = Color(0xFF4FC3F7)
    val draft = Color(0x664FC3F7)   // background of tokens the drafter proposed and the target kept
    val ok = Color(0xFF66BB6A)
    val bad = Color(0xFFEF5350)
    val warn = Color(0xFFFFB74D)
}

fun fmt(v: Double?, digits: Int = 2): String = v?.let { "%.${digits}f".format(it) } ?: "-"

@Composable
fun PhaseChip(p: Phase, replay: Boolean) {
    val (label, color) = when (p) {
        Phase.IDLE -> "idle" to Palette.ar
        Phase.COOLING -> "cooling" to Palette.warn
        Phase.LOADING -> "loading model" to Palette.warn
        Phase.PREFILL -> "prefill" to Palette.warn
        Phase.DECODING -> (if (replay) "replay" else "live") to Palette.sd
        Phase.DONE -> (if (replay) "recorded" else "done") to Palette.ok
        Phase.FAILED -> "failed" to Palette.bad
    }
    Box(Modifier.background(color.copy(alpha = 0.2f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(label, color = color, fontSize = 11.sp)
    }
}

/** Generated text; tokens the drafter supplied are shaded, so each verify step's burst is visible. */
@Composable
fun TokenText(steps: List<StepRec>, speculative: Boolean, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(steps.size) { scroll.animateScrollTo(scroll.maxValue) }
    val text = remember(steps.size, speculative) {
        buildAnnotatedString {
            for (s in steps) {
                for (i in s.ids.indices) {
                    val t = s.texts.getOrElse(i) { "" }
                    if (s.origin(i, speculative) == Origin.DRAFT) withStyle(SpanStyle(background = Palette.draft)) { append(t) }
                    else append(t)
                }
            }
        }
    }
    Box(modifier.verticalScroll(scroll)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, lineHeight = 20.sp)
    }
}

@Composable
fun Stat(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RunPane(p: PaneState, modifier: Modifier = Modifier) {
    val accent = if (p.role == Role.SD) Palette.sd else Palette.ar
    Surface(modifier, tonalElevation = 2.dp, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(accent, RoundedCornerShape(5.dp)))
                Spacer(Modifier.width(6.dp))
                Text(p.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                PhaseChip(p.phase, p.replay)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("tokens", "${p.tokens}")
                Stat(if (p.headline == com.mobispec.demo.manifest.Headline.GENERATION) "gen tok/s" else "tok/s", fmt(p.shownTps, 1), accent)
                if (p.speculative) Stat("AL", fmt(p.done["al"] ?: p.liveAl))
                Stat("TTFT ms", fmt(ttftMs(p), 0))
            }
            p.message?.let { Text(it, fontSize = 12.sp, color = if (p.phase == Phase.FAILED) Palette.bad else Palette.warn) }
            Spacer(Modifier.height(6.dp))
            TokenText(p.steps, p.speculative, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

fun ttftMs(p: PaneState): Double? {
    val loaded = p.marks["loaded"] ?: return null
    val first = p.steps.firstOrNull()?.tUs ?: return null
    return (first - loaded) / 1000.0
}

/** Cumulative tokens against time; the slope is the decode rate. */
@Composable
fun TokenTimeChart(series: List<Pair<PaneState, Color>>, align: Align, modifier: Modifier = Modifier) {
    val pts = series.map { (p, c) ->
        val t0 = when {
            p.steps.isEmpty() -> 0L
            align == Align.E2E -> p.marks["loaded"] ?: p.steps.first().tUs
            else -> p.steps.first().tUs
        }
        var n = 0
        val xs = p.steps.map { s -> n += s.ids.size; ((s.tUs - t0) / 1e6).toFloat() to n.toFloat() }
        xs to c
    }
    val maxT = (pts.flatMap { it.first }.maxOfOrNull { it.first } ?: 1f).coerceAtLeast(0.5f)
    val maxN = (pts.flatMap { it.first }.maxOfOrNull { it.second } ?: 1f).coerceAtLeast(8f)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val w = size.width; val h = size.height
            drawLine(Color.Gray, Offset(0f, h), Offset(w, h))
            drawLine(Color.Gray, Offset(0f, 0f), Offset(0f, h))
            for ((xy, c) in pts) {
                if (xy.isEmpty()) continue
                val path = Path()
                path.moveTo(0f, h)
                var lastY = h
                for ((t, n) in xy) {
                    val x = t / maxT * w
                    val y = h - n / maxN * h
                    path.lineTo(x, lastY); path.lineTo(x, y); lastY = y   // tokens arrive in bursts
                }
                drawPath(path, c, style = Stroke(width = 4f))
            }
        }
        Text("tokens (max ${maxN.toInt()}) vs seconds since ${if (align == Align.E2E) "prompt" else "first token"} (max %.1f)".format(maxT),
            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun BarChart(bars: List<Triple<String, Double, Color>>, unit: String, modifier: Modifier = Modifier) {
    val max = (bars.maxOfOrNull { it.second } ?: 1.0).coerceAtLeast(1e-6)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((label, v, c) in bars) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.width(96.dp), fontSize = 12.sp, maxLines = 1)
                Box(Modifier.weight(1f).height(16.dp)) {
                    Box(Modifier.fillMaxWidth((v / max).toFloat().coerceIn(0.01f, 1f)).fillMaxSize().background(c, RoundedCornerShape(3.dp)))
                }
                Text(" %.1f %s".format(v, unit), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** The tree verified in one step: nodes by depth, the accepted path highlighted. */
@Composable
fun TreeView(step: StepRec?, modifier: Modifier = Modifier) {
    val tree = step?.tree
    if (tree.isNullOrEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("no tree for this step", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val depth = IntArray(tree.size)
    for (i in tree.indices) { val p = tree[i][0]; depth[i] = if (p in 0 until i) depth[p] + 1 else 0 }
    val byDepth = tree.indices.groupBy { depth[it] }
    val slot = IntArray(tree.size)
    byDepth.values.forEach { nodes -> nodes.forEachIndexed { k, i -> slot[i] = k } }
    val maxD = depth.maxOrNull() ?: 0
    val maxW = byDepth.values.maxOf { it.size }
    val accepted = step.path?.toSet() ?: emptySet()
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            fun pos(i: Int) = Offset(
                (depth[i] + 0.5f) / (maxD + 1) * size.width,
                (slot[i] + 0.5f) / maxW * size.height,
            )
            for (i in tree.indices) {
                val p = tree[i][0]
                if (p in 0 until i) drawLine(if (i in accepted && p in accepted) Palette.sd else Color.Gray, pos(p), pos(i), strokeWidth = if (i in accepted) 4f else 1.5f)
            }
            for (i in tree.indices) drawCircle(if (i in accepted) Palette.sd else Color.DarkGray, radius = 7f, center = pos(i))
        }
        Text("${tree.size} nodes verified, ${accepted.size - 1} draft tokens accepted (depth ${maxD})",
            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
