package com.mobispec.demo.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mobispec.demo.manifest.Backend
import com.mobispec.demo.manifest.Role
import com.mobispec.demo.manifest.Scenario
import java.io.File

/**
 * One run in the demo's single layout (the "option 1" of the 2026-10-27 slides): AR and SD differ only in their accent
 * colour, so two of them sit side by side, on one screen or as two recordings. `k` scales every size (1 = full screen).
 * `stats` false drops the tokens, time, tokens-per-call and progress rows and `chart` false the tokens-per-call bars; the
 * text below takes their room.
 */
@Composable
fun RunView(
    p: PaneState, r: RaceState, sc: Scenario?, modelsPath: String?, k: Float, chart: Boolean = true, stats: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val accent = Palette.accent(p.role)
    val total = sc?.nPredict ?: p.tokens
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MethodBadge(p.title, p.role, k)
            Spacer(Modifier.weight(1f))
            PhaseChip(p.phase, p.replay, k)
        }
        Row(Modifier.padding(top = (10 * k).dp), verticalAlignment = Alignment.Bottom) {
            Text(sc?.model ?: "", color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = (17 * k).sp, maxLines = 1)
            Text("   " + listOfNotNull(sc?.let { backendLabel(it) }, titleDetail(p.title)).joinToString(" - "), color = Palette.muted,
                fontSize = (13 * k).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        sc?.prompts?.firstOrNull { it.id == r.promptId }?.let { pr ->
            Row(Modifier.padding(top = (12 * k).dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pr.text.trim(), color = Palette.muted, fontSize = (13 * k).sp, lineHeight = (18 * k).sp, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (pr.image != null && modelsPath != null) Thumbnail(File(modelsPath, pr.image), k)
            }
        }
        Spacer(Modifier.height((18 * k).dp))

        // the speed: this run's rate, and how it compares with AR
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmt(p.shownTps, 1), color = accent, fontWeight = FontWeight.Bold, fontSize = (80 * k).sp, lineHeight = (80 * k).sp, style = Numbers)
            Text(" tok/s", color = Palette.muted, fontSize = (20 * k).sp, modifier = Modifier.padding(bottom = (12 * k).dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = (8 * k).dp)) {
                val x = if (p.role == Role.SD) r.xar else 1.0
                Text("${fmt(x)}x", color = if (p.role == Role.SD) accent else Palette.muted, fontWeight = FontWeight.Bold, fontSize = (34 * k).sp, style = Numbers)
                Text(if (p.role == Role.SD) "vs AR" else "baseline", color = Palette.muted, fontSize = (12 * k).sp)
            }
        }
        if (stats) {
            Spacer(Modifier.height((16 * k).dp))
            Row(Modifier.fillMaxWidth()) {
                Metric("TOKENS", "${p.tokens}", "/ $total", k, Modifier.weight(1f))
                Metric("TIME", fmt(elapsedSec(p, r.align), 1), "s", k, Modifier.weight(1f))
                Metric("TOKENS / CALL", fmt(p.done["al"] ?: p.liveAl), "", k, Modifier.weight(1f))
            }
            Spacer(Modifier.height((14 * k).dp))
            TokenProgress(p.tokens, total, accent, height = (10 * k).dp)
            Spacer(Modifier.height((6 * k).dp))
            Row {
                val done = p.phase == Phase.DONE
                Text(if (done) "finished in ${fmt(elapsedSec(p, r.align), 2)} s" else "${p.tokens} of $total tokens",
                    color = if (done) Palette.ok else Palette.muted, fontSize = (12 * k).sp, fontWeight = if (done) FontWeight.Bold else FontWeight.Normal,
                    style = Numbers, maxLines = 1, modifier = Modifier.weight(1f))
                if (p.role == Role.SD && r.lossless?.identical == true) {
                    Text("same ${r.lossless.compared} tokens as AR", color = Palette.ok, fontSize = (12 * k).sp, style = Numbers, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height((18 * k).dp))

        // the process: one bar per target call
        if (chart) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("TOKENS PER TARGET CALL", color = Palette.muted, fontSize = (11 * k).sp, fontWeight = FontWeight.Bold, letterSpacing = (1 * k).sp,
                    maxLines = 1, modifier = Modifier.weight(1f))
                Text("${p.steps.size} calls", color = Palette.muted, fontSize = (12 * k).sp, style = Numbers, maxLines = 1)
            }
            Spacer(Modifier.height((6 * k).dp))
            StepStrip(p.steps, p.speculative, sc?.stepCap ?: 8, accent, Modifier.fillMaxWidth().height((88 * k).dp), bar = (7 * k).dp, gap = (3 * k).dp)
            Spacer(Modifier.height((6 * k).dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((6 * k).dp)) {
                Swatch(accent, k); Text("target token", color = Palette.muted, fontSize = (11 * k).sp, maxLines = 1)
                Spacer(Modifier.width((8 * k).dp))
                Swatch(accent.copy(alpha = if (p.speculative) 0.45f else 0.12f), k)
                Text(if (p.speculative) "drafted, accepted" else "no drafter", color = Palette.muted, fontSize = (11 * k).sp, maxLines = 1)
            }
            Spacer(Modifier.height((14 * k).dp))
        }
        if (stats || chart) {
            HorizontalDivider(color = Palette.line)
            Spacer(Modifier.height((12 * k).dp))
        }
        if (p.steps.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(p.message ?: waiting(p.phase), color = if (p.phase == Phase.FAILED) Palette.bad else Palette.muted, fontSize = (16 * k).sp)
            }
        } else {
            TokenText(p.steps, p.speculative, Modifier.fillMaxWidth().weight(1f), fontSize = (18 * k).sp, lineHeight = (27 * k).sp,
                fade = Palette.bg)
        }
    }
}

/**
 * One run on the whole screen, for recording. `role` null follows the run that is going (AR first, then SD; the
 * finished AR run stays through the cooldown). Tap to show or hide the controls.
 */
@Composable
fun FocusScreen(r: RaceState, role: Role?, sc: Scenario?, chart: Boolean, stats: Boolean, vm: DemoViewModel) {
    val shown = role ?: if (r.sd.phase != Phase.IDLE && r.sd.phase != Phase.COOLING) Role.SD else Role.AR
    val p = if (shown == Role.SD) r.sd else r.ar
    var controls by remember { mutableStateOf(!r.running) }
    LaunchedEffect(r.running) { if (r.running) controls = false }
    Immersive()
    Box(
        Modifier.fillMaxSize().background(Palette.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controls = !controls },
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 22.dp, vertical = 18.dp)) {
            RunView(p, r, sc, sc?.let { vm.modelsPath(it) }, 1f, chart, stats, Modifier.weight(1f))
            if (controls) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.running) {
                        Button(onClick = vm::cancel) { Text("Stop") }
                    } else {
                        Button(onClick = { vm.replayOne(p.role) }, enabled = p.record != null) { Text("Replay ${shortTitle(p.title)}") }
                    }
                    OutlinedButton(onClick = { vm.go(Screen.Focus(if (shown == Role.SD) Role.AR else Role.SD)) }) {
                        Text(shortTitle(if (shown == Role.SD) r.ar.title else r.sd.title), maxLines = 1)
                    }
                    OutlinedButton(onClick = { vm.go(Screen.Race) }) { Text("Both") }
                }
            }
        }
        Countdown(r.countdown, Palette.accent(p.role))
    }
}

/** 3, 2, 1 over everything before a replay starts. */
@Composable
fun Countdown(n: Int?, color: Color) {
    if (n == null) return
    Box(Modifier.fillMaxSize().background(Palette.bg.copy(alpha = 0.88f)), contentAlignment = Alignment.Center) {
        Text("$n", color = color, fontSize = 160.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun Metric(label: String, value: String, unit: String, k: Float, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = Palette.muted, fontSize = (11 * k).sp, fontWeight = FontWeight.Bold, letterSpacing = (1 * k).sp, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = Palette.text, fontSize = (30 * k).sp, fontWeight = FontWeight.SemiBold, style = Numbers, maxLines = 1)
            if (unit.isNotEmpty()) Text(" $unit", color = Palette.muted, fontSize = (14 * k).sp, maxLines = 1, modifier = Modifier.padding(bottom = (4 * k).dp))
        }
    }
}

/** The VLM's input page, small, next to the question. */
@Composable
private fun Thumbnail(f: File, k: Float) {
    val bmp = remember(f.path) {
        runCatching { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap() }.getOrNull()
    } ?: return
    Spacer(Modifier.width((12 * k).dp))
    Image(bmp, contentDescription = "input page", contentScale = ContentScale.Fit,
        modifier = Modifier.height((64 * k).dp).clip(RoundedCornerShape(4.dp)).border(1.dp, Palette.line, RoundedCornerShape(4.dp)))
}

@Composable
fun Swatch(c: Color, k: Float = 1f) = Box(Modifier.size((10 * k).dp).background(c, RoundedCornerShape(2.dp)))

private fun waiting(p: Phase) = when (p) {
    Phase.IDLE -> "ready"
    Phase.COOLING -> "cooling down"
    Phase.LOADING -> "loading the model"
    Phase.PREFILL -> "reading the prompt"
    else -> ""
}

fun backendLabel(sc: Scenario): String {
    fun name(b: Backend) = if (b == Backend.GPU) "Adreno GPU" else "Hexagon NPU"
    return if (sc.backend == sc.drafter) name(sc.backend) else "${name(sc.backend)} + ${name(sc.drafter)} draft"
}

/** Hides the status and navigation bars while shown; a swipe brings them back for a moment. */
@Composable
fun Immersive() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.activity()?.window
        val c = window?.let { WindowCompat.getInsetsController(it, view) }
        c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { c?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
