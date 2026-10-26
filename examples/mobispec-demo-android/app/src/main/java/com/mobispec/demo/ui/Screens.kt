package com.mobispec.demo.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobispec.demo.manifest.Backend
import com.mobispec.demo.manifest.Kind
import com.mobispec.demo.manifest.Method
import com.mobispec.demo.manifest.PageRef
import com.mobispec.demo.manifest.Prompt
import com.mobispec.demo.manifest.Role
import com.mobispec.demo.manifest.Scenario
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoApp(vm: DemoViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val screen = ui.screen
    BackHandler(enabled = screen != Screen.Home) {
        vm.cancel()
        vm.go(when (screen) {
            is Screen.Log -> if (ui.race != null) Screen.Race else Screen.Home
            is Screen.AgentReplay -> Screen.ScenarioScreen(screen.scenarioId)
            is Screen.Race, is Screen.Board -> ui.race?.scenarioId?.let { Screen.ScenarioScreen(it) }
                ?: ui.board?.scenarioId?.let { Screen.ScenarioScreen(it) } ?: Screen.Home
            else -> Screen.Home
        })
    }
    Scaffold(topBar = {
        TopAppBar(title = {
            Text(when (screen) {
                Screen.Home -> "MobiSpec on-device SD"
                is Screen.ScenarioScreen -> vm.scenario(screen.id)?.title ?: ""
                Screen.Race -> "AR vs speculative"
                Screen.Board -> "Method board"
                is Screen.Log -> "Run log"
                is Screen.AgentReplay -> "AR vs SD, side by side"
            }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 18.sp)
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (screen) {
                Screen.Home -> HomeScreen(ui, vm)
                is Screen.ScenarioScreen -> vm.scenario(screen.id)?.let { ScenarioScreen(it, ui, vm) }
                Screen.Race -> ui.race?.let { RaceScreen(it, vm) }
                Screen.Board -> ui.board?.let { BoardScreen(it, ui, vm) }
                is Screen.Log -> LogScreen(screen)
                is Screen.AgentReplay -> AgentReplayScreen()
            }
        }
    }
}

@Composable
fun HomeScreen(ui: UiState, vm: DemoViewModel) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { DeviceCard(ui, vm) }
        ui.manifestError?.let { err ->
            item {
                Card { Column(Modifier.padding(12.dp)) {
                    Text("No usable demo manifest", fontWeight = FontWeight.Bold, color = Palette.bad)
                    Text(err, fontSize = 12.sp)
                    Text("Push models and demo-manifest.json with tools/push_models.py, then reload.", fontSize = 12.sp)
                    TextButton(onClick = vm::reload) { Text("Reload") }
                } }
            }
        }
        val scenarios = ui.manifest?.scenarios ?: emptyList()
        // the demo's three parts (user 2026-10-26); document QA stays reachable under the last heading
        val groups = listOf(
            "GPU - LLM 텍스트 생성 (Adreno 타깃과 드래프트)" to scenarios.filter { it.backend == Backend.GPU && it.kind == Kind.LLM },
            "NPU - LLM 텍스트 생성 (Hexagon 타깃과 드래프트)" to scenarios.filter { it.backend == Backend.NPU && it.kind == Kind.LLM },
            "NPU - VLM 에이전트 (폰을 조작하는 작업 시간)" to scenarios.filter { it.kind == Kind.AGENT },
            "그 밖의 데모" to scenarios.filter { it.kind == Kind.VLM },
        )
        for ((heading, group) in groups) {
            if (group.isEmpty()) continue
            item { Text(heading, style = MaterialTheme.typography.titleMedium) }
            items(group, key = { it.id }) { sc ->
                val missing = ui.missing[sc.id].orEmpty()
                val blocked = ui.pinned != null && ui.pinned != sc.drafter
                Card(Modifier.fillMaxWidth().clickable { vm.go(Screen.ScenarioScreen(sc.id)) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(sc.title, fontWeight = FontWeight.Bold)
                        Text(sc.subtitle, fontSize = 12.sp)
                        Text("${sc.kind} - ${sc.sdMethods.joinToString { it.title }}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (missing.isNotEmpty()) Text("${missing.size} file(s) missing: ${missing.take(3).joinToString()}", fontSize = 11.sp, color = Palette.bad)
                        if (blocked) Text("Locked this boot: ${ui.pinned}-draft runs already happened (reboot to use)", fontSize = 11.sp, color = Palette.warn)
                    }
                }
            }
        }
        item { SettingsCard(ui, vm) }
        item { Text("Manifest ${ui.manifest?.generated ?: "-"}\nModels: ${ui.modelsDir}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun DeviceCard(ui: UiState, vm: DemoViewModel) {
    val d = ui.device
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(d?.let { "${it.model} (${it.soc})" } ?: "reading device...", fontWeight = FontWeight.Bold)
            if (d != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("battery", "${d.batteryPct}%${if (d.charging) "+" else ""}", if (d.batteryPct in 0 until 10) Palette.bad else Palette.ok)
                    Stat("batt C", fmt(d.batteryTempC, 1))
                    Stat("thermal", d.thermalLabel, if (d.thermalStatus >= 2) Palette.warn else Palette.ok)
                    Stat("headroom", if (d.headroom.isNaN()) "-" else fmt(d.headroom.toDouble()))
                    Stat("free MB", "${d.availMemMb}")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Drafter pinned this boot: ${ui.pinned ?: "none"} (boot ${d.bootCount})", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::resetBootLock) { Text("Reset pin") }
                }
            }
        }
    }
}

@Composable
fun SettingsCard(ui: UiState, vm: DemoViewModel) {
    val s = ui.settings
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Run settings", fontWeight = FontWeight.Bold)
            Text("Start a run only below thermal headroom %.2f".format(s.headroomLimit), fontSize = 12.sp)
            Slider(value = s.headroomLimit, onValueChange = { vm.settings(s.copy(headroomLimit = it)) }, valueRange = 0.3f..1.0f)
            Text("... and below battery %.0f C (and thermal status under moderate)".format(s.batteryHotC), fontSize = 12.sp)
            Slider(value = s.batteryHotC.toFloat(), onValueChange = { vm.settings(s.copy(batteryHotC = it.toDouble())) }, valueRange = 30f..45f)
            Text("Minimum gap between back-to-back runs: ${s.minGapSec}s", fontSize = 12.sp)
            Slider(value = s.minGapSec.toFloat(), onValueChange = { vm.settings(s.copy(minGapSec = it.toInt())) }, valueRange = 0f..180f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Line runs up on the prompt (e2e) instead of the first token", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Switch(checked = s.align == Align.E2E, onCheckedChange = { vm.settings(s.copy(align = if (it) Align.E2E else Align.DECODE)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Record draft trees (MAST)", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Switch(checked = s.tree, onCheckedChange = { vm.settings(s.copy(tree = it)) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenarioScreen(sc: Scenario, ui: UiState, vm: DemoViewModel) {
    if (sc.kind == Kind.AGENT) return AgentScenarioScreen(sc, ui, vm)
    var promptId by rememberSaveable(sc.id) { mutableStateOf(sc.prompts.firstOrNull()?.id ?: "") }
    var custom by rememberSaveable(sc.id) { mutableStateOf("") }
    var methodId by rememberSaveable(sc.id) { mutableStateOf(sc.sdMethods.firstOrNull()?.id ?: "") }
    val prompt: Prompt? = if (promptId == CUSTOM && custom.isNotBlank())
        Prompt("custom-${custom.hashCode().toUInt()}", "Custom", custom, "custom", null, null)
    else sc.prompts.firstOrNull { it.id == promptId }
    val method = sc.sdMethods.firstOrNull { it.id == methodId }
    val missing = ui.missing[sc.id].orEmpty()
    val locked = ui.pinned != null && ui.pinned != sc.drafter

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(sc.subtitle, fontSize = 13.sp)
        Text("Prompt", style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(sc.prompts, key = { it.id }) { p ->
                FilterChip(selected = promptId == p.id, onClick = { promptId = p.id }, label = { Text(p.title) })
            }
            if (sc.allowCustomPrompt) item {
                FilterChip(selected = promptId == CUSTOM, onClick = { promptId = CUSTOM }, label = { Text("Custom") })
            }
        }
        if (promptId == CUSTOM) {
            OutlinedTextField(custom, { custom = it }, Modifier.fillMaxWidth(), label = { Text("Your prompt") }, minLines = 2)
        } else prompt?.let { p ->
            if (sc.kind == Kind.VLM && p.image != null) PageImage(File(vm.modelsPath(sc), p.image))
            Text(p.text, fontSize = 13.sp, maxLines = 8)
        }
        HorizontalDivider()
        Text("Speculative method", style = MaterialTheme.typography.titleSmall)
        for (m in sc.sdMethods) MethodCard(m, selected = m.id == methodId, page = prompt?.refs?.get(m.id), ar = prompt?.refs?.get(sc.ar(m.target)?.id ?: "")) { methodId = m.id }
        HorizontalDivider()
        if (missing.isNotEmpty()) Text("Missing on device: ${missing.joinToString()}", color = Palette.bad, fontSize = 12.sp)
        if (locked) Text("This boot is pinned to ${ui.pinned}-draft runs. Reboot before running this scenario.", color = Palette.warn, fontSize = 12.sp)
        val ready = prompt != null && method != null && missing.isEmpty() && !locked
        val cached = remember(prompt?.id, method?.id, ui.race?.running, ui.board?.running) {
            if (prompt != null && method != null) vm.cachedAr(sc, prompt, method.target) else null
        }
        Button(onClick = { vm.race(sc, prompt!!, method!!, replayAr = false) }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
            Text("Race live: ${sc.ar(method?.target)?.title ?: "AR"}, then ${method?.title ?: ""}")
        }
        OutlinedButton(onClick = { vm.race(sc, prompt!!, method!!, replayAr = true) }, enabled = ready && cached != null, modifier = Modifier.fillMaxWidth()) {
            Text(if (cached != null) "Race: ${method?.title} live vs AR recorded ${time(cached.startedAtMs)}" else "Race vs recorded AR (run AR once first)")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.board(sc, prompt!!) }, enabled = prompt != null && missing.isEmpty() && !locked, modifier = Modifier.weight(1f)) {
                Text("All methods")
            }
            for (m in sc.methods.filter { it.role != Role.SD }) {
                OutlinedButton(onClick = { vm.runOne(sc, prompt!!, m) }, enabled = prompt != null && missing.isEmpty() && !locked, modifier = Modifier.weight(1f)) {
                    Text(m.title, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun MethodCard(m: Method, selected: Boolean, page: PageRef? = null, ar: PageRef? = null, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) Palette.sd else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                m.reference?.let { r -> Text("ref ${fmt(r.tps, 1)} tok/s, AL ${fmt(r.al)}, ${fmt(r.xar)}x", fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
            if (m.blurb.isNotEmpty()) Text(m.blurb, fontSize = 12.sp)
            m.reference?.note?.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            page?.let { pr ->
                Text("this input, measured: decode ${fmt(pr.tps, 1)} / gen ${fmt(pr.generationTps, 1)} tok/s, AL ${fmt(pr.al)}" +
                    (ar?.tps?.let { a -> ", AR ${fmt(a, 1)}" } ?: ""), fontSize = 11.sp, color = Palette.sd)
            }
        }
    }
}

@Composable
fun PageImage(f: File) {
    val bmp = remember(f.path) {
        runCatching {
            val o = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(f.path, o)?.asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) Image(bmp, contentDescription = "input page", modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp), contentScale = ContentScale.Fit)
    else Text("(page image not on device: ${f.name})", fontSize = 11.sp)
}

@Composable
fun RaceScreen(r: RaceState, vm: DemoViewModel) {
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RunPane(r.ar, Modifier.fillMaxWidth().weight(1f))
        RunPane(r.sd, Modifier.fillMaxWidth().weight(1f))
        // the two text panes are the demo; everything below stays compact (per-pane numbers are in the panes)
        Row(Modifier.fillMaxWidth().height(96.dp)) {
            TokenTimeChart(listOf(r.ar to Palette.ar, r.sd to Palette.sd), r.align, Modifier.weight(1f).padding(end = 6.dp))
            if (r.sd.steps.lastOrNull()?.tree != null) TreeView(r.sd.steps.lastOrNull(), Modifier.width(120.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Stat("x AR", fmt(r.xar), Palette.sd)
            Stat("e2e x", fmt(ratio(r.sd.record?.e2eTps, r.ar.record?.e2eTps)))
            r.lossless?.let { l ->
                Text(
                    if (l.identical) "Lossless\n${l.compared} tokens identical" else "Differs from\nreference at token ${l.firstDiff}",
                    color = if (l.identical) Palette.ok else Palette.bad, fontSize = 12.sp,
                )
            }
        }
        r.note?.let { note ->
            var open by remember(note) { mutableStateOf(false) }
            Text(note, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (open) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { open = !open })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.running) {
                if (r.ar.phase == Phase.COOLING || r.sd.phase == Phase.COOLING) OutlinedButton(onClick = vm::skipCooldown) { Text("Start now") }
                Button(onClick = vm::cancel) { Text("Stop") }
            } else {
                Button(onClick = vm::replayRace, enabled = r.ar.record != null && r.sd.record != null) { Text("Replay race") }
            }
            r.sd.record?.let { rec -> OutlinedButton(onClick = { vm.go(Screen.Log(rec)) }) { Text("SD log") } }
            r.ar.record?.let { rec -> OutlinedButton(onClick = { vm.go(Screen.Log(rec)) }) { Text("AR log") } }
        }
    }
}

@Composable
fun BoardScreen(b: BoardState, ui: UiState, vm: DemoViewModel) {
    val sc = vm.scenario(b.scenarioId)
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val arTps = b.rows.firstOrNull { it.method.role == Role.AR }?.pane?.record?.headlineTps(b.headline)
        val done = b.rows.filter { it.pane.record != null }
        if (done.isNotEmpty()) {
            BarChart(done.map { r ->
                Triple(r.method.title, r.pane.record!!.headlineTps(b.headline) ?: 0.0, if (r.method.role == Role.SD) Palette.sd else Palette.ar)
            }, "tok/s", Modifier.fillMaxWidth())
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(b.rows, key = { it.method.id }) { row ->
                val rec = row.pane.record
                Card(Modifier.fillMaxWidth().clickable(enabled = rec != null) { rec?.let { vm.go(Screen.Log(it)) } }) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.method.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            PhaseChip(row.pane.phase, false)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Stat("tok/s", fmt(rec?.headlineTps(b.headline) ?: row.pane.shownTps, 1))
                            Stat("AL", fmt(rec?.al ?: row.pane.liveAl))
                            Stat("x AR", fmt(ratio(rec?.headlineTps(b.headline), arTps)))
                            Stat("TTFT ms", fmt(rec?.ttftMs, 0))
                            Stat("ref x", fmt(row.method.reference?.xar))
                        }
                        row.pane.message?.let { Text(it, fontSize = 11.sp, color = if (row.pane.phase == Phase.FAILED) Palette.bad else Palette.warn) }
                        if (row.pane.phase == Phase.DECODING) TokenText(row.pane.steps, row.pane.speculative, Modifier.fillMaxWidth().heightIn(max = 120.dp))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (b.running) {
                if (b.rows.any { it.pane.phase == Phase.COOLING }) OutlinedButton(onClick = vm::skipCooldown) { Text("Start now") }
                Button(onClick = vm::cancel) { Text("Stop") }
            } else if (sc != null) {
                OutlinedButton(onClick = { vm.go(Screen.ScenarioScreen(sc.id)) }) { Text("Back") }
            }
        }
    }
}

@Composable
fun LogScreen(s: Screen.Log) {
    val r = s.record
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
        Text("${r.methodTitle} - ${time(r.startedAtMs)} - exit ${r.exitCode} - ${r.wallMs} ms wall", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Command", fontWeight = FontWeight.SemiBold)
        Text(r.command.joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Text("Driver numbers", fontWeight = FontWeight.SemiBold)
        Text(r.done.entries.joinToString("\n") { "${it.key} = ${it.value}" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text("load ${fmt(r.loadMs, 0)} ms, TTFT ${fmt(r.ttftMs, 0)} ms, e2e ${fmt(r.e2eTps, 1)} tok/s", fontSize = 12.sp)
        Text("device at start: ${r.device.entries.joinToString { "${it.key}=${fmt(it.value, 1)}" }}", fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Text("Output", fontWeight = FontWeight.SemiBold)
        Text(r.text, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text("Log tail", fontWeight = FontWeight.SemiBold)
        Text(r.logTail.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

private const val CUSTOM = "__custom__"

private fun ratio(a: Double?, b: Double?): Double? = if (a != null && b != null && b > 0) a / b else null

private fun time(ms: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ms))
