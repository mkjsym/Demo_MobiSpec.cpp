package com.mobispec.demo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobispec.demo.manifest.Role
import com.mobispec.demo.run.Origin
import com.mobispec.demo.run.StepRec

object Palette {
    val bg = Color(0xFF0B0F14)
    val panel = Color(0xFF131A22)
    val line = Color(0xFF263241)
    val text = Color(0xFFE6EBF2)
    val muted = Color(0xFF8B97A8)
    val ar = Color(0xFFA7B1C0)
    val sd = Color(0xFF4FC3F7)
    val ok = Color(0xFF66BB6A)
    val bad = Color(0xFFEF5350)
    val warn = Color(0xFFFFB74D)

    fun accent(role: Role) = if (role == Role.SD) sd else ar
}

/** Tabular figures, so a changing number does not shift its neighbours. */
val Numbers = TextStyle(fontFeatureSettings = "tnum")

fun fmt(v: Double?, digits: Int = 2): String = v?.let { "%.${digits}f".format(it) } ?: "-"

@Composable
fun PhaseChip(p: Phase, replay: Boolean, k: Float = 0.77f) {
    val (label, color) = when (p) {
        Phase.IDLE -> "READY" to Palette.muted
        Phase.COOLING -> "COOLING" to Palette.warn
        Phase.LOADING -> "LOADING" to Palette.warn
        Phase.PREFILL -> "PREFILL" to Palette.warn
        Phase.DECODING -> (if (replay) "REPLAY" else "LIVE") to Palette.sd
        Phase.DONE -> (if (replay) "RECORDED" else "DONE") to Palette.ok
        Phase.FAILED -> "FAILED" to Palette.bad
    }
    StatusChip(label, color, k)
}

/** A dot and an upper-case word on a tint of its colour; `k` scales it (1 = the full-screen size). */
@Composable
fun StatusChip(label: String, color: Color, k: Float = 0.77f) {
    Row(
        Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = (12 * k).dp, vertical = (5 * k).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size((8 * k).dp).background(color, CircleShape))
        Spacer(Modifier.width((6 * k).dp))
        Text(label, color = color, fontSize = (13 * k).sp, fontWeight = FontWeight.Bold, letterSpacing = (1 * k).sp, maxLines = 1, softWrap = false)
    }
}

/** The method's short name on its accent colour; AR and SD panes differ only in this colour. `k` as in [StatusChip]. */
@Composable
fun MethodBadge(title: String, role: Role, k: Float = 0.64f) = MethodBadge(title, Palette.accent(role), k)

@Composable
fun MethodBadge(title: String, color: Color, k: Float = 0.64f) {
    Box(Modifier.background(color, RoundedCornerShape((8 * k).dp)).padding(horizontal = (12 * k).dp, vertical = (4 * k).dp)) {
        Text(shortTitle(title), color = Palette.bg, fontWeight = FontWeight.Black, fontSize = (22 * k).sp, maxLines = 1, softWrap = false)
    }
}

/** "Ours (dynamic registers)" -> "Ours"; the part in parentheses is [titleDetail]. */
fun shortTitle(title: String) = title.substringBefore(" (")

fun titleDetail(title: String): String? = title.substringAfter(" (", "").removeSuffix(")").ifEmpty { null }

/**
 * Generated text. Tokens the drafter supplied and the target kept are set in the SD colour, the
 * target's own tokens in the text colour. `fade` is the background behind, blended over the top
 * edge where lines scroll away.
 */
@Composable
fun TokenText(
    steps: List<StepRec>, speculative: Boolean, modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp, lineHeight: TextUnit = 20.sp, fade: Color? = null,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(steps.size) { scroll.animateScrollTo(scroll.maxValue) }
    val text = remember(steps.size, speculative) {
        buildAnnotatedString {
            for (s in steps) {
                for (i in s.ids.indices) {
                    val t = s.texts.getOrElse(i) { "" }
                    if (s.origin(i, speculative) == Origin.DRAFT) withStyle(SpanStyle(color = Palette.sd)) { append(t) } else append(t)
                }
            }
        }
    }
    Box(modifier) {
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Text(text, fontSize = fontSize, lineHeight = lineHeight, color = Palette.text)
        }
        if (fade != null && scroll.value > 0) {
            Box(Modifier.fillMaxWidth().height(28.dp).background(Brush.verticalGradient(listOf(fade, Color.Transparent))))
        }
    }
}

@Composable
fun Stat(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, style = Numbers)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Tokens generated out of the run's budget. */
@Composable
fun TokenProgress(tokens: Int, total: Int, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val f = if (total > 0) (tokens.toFloat() / total).coerceIn(0f, 1f) else 0f
    Box(modifier.fillMaxWidth().height(height).background(Palette.line, RoundedCornerShape(50))) {
        if (f > 0f) Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(color, RoundedCornerShape(50)))
    }
}

/**
 * One bar per target call, oldest on the left: the solid unit is the target's own token, the
 * lighter part above it the drafted tokens it accepted. `cap` is the scenario's most tokens per
 * call, so the AR and SD strips share a scale.
 */
@Composable
fun StepStrip(steps: List<StepRec>, speculative: Boolean, cap: Int, color: Color, modifier: Modifier = Modifier, bar: Dp = 7.dp, gap: Dp = 3.dp) {
    Canvas(modifier) {
        val bw = bar.toPx(); val g = gap.toPx()
        val unit = size.height / cap
        val slots = ((size.width + g) / (bw + g)).toInt().coerceAtLeast(1)
        val shown = if (steps.size > slots) steps.subList(steps.size - slots, steps.size) else steps
        drawLine(Palette.line, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        drawLine(Palette.line.copy(alpha = 0.5f), Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f)
        shown.forEachIndexed { k, s ->
            val x = k * (bw + g)
            val n = s.ids.size.coerceIn(1, cap)
            drawRect(color, Offset(x, size.height - unit), Size(bw, unit - 1f))
            if (speculative && n > 1) drawRect(color.copy(alpha = 0.45f), Offset(x, size.height - n * unit), Size(bw, (n - 1) * unit - 1f))
        }
    }
}

/** Seconds from the run's start (first token, or the prompt when lined up end to end) to its latest token. */
fun elapsedSec(p: PaneState, align: Align): Double? {
    val last = p.steps.lastOrNull()?.tUs ?: return null
    val t0 = if (align == Align.E2E) p.marks["loaded"] ?: p.steps.first().tUs else p.steps.first().tUs
    return (last - t0) / 1e6
}

/** Label and value above a rounded bar per row, as on the slides' infographic. */
@Composable
fun BarChart(bars: List<Triple<String, Double, Color>>, unit: String, modifier: Modifier = Modifier) {
    val max = (bars.maxOfOrNull { it.second } ?: 1.0).coerceAtLeast(1e-6)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, v, c) in bars) {
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(label, color = c, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    Text("%.1f %s".format(v, unit), color = c, fontWeight = FontWeight.Bold, fontSize = 16.sp, style = Numbers)
                }
                Spacer(Modifier.height(3.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).background(Palette.line, RoundedCornerShape(50))) {
                    Box(Modifier.fillMaxWidth((v / max).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().background(c, RoundedCornerShape(50)))
                }
            }
        }
    }
}
