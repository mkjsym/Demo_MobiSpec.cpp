package com.mobispec.demo.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobispec.demo.agent.AgentHub
import com.mobispec.demo.agent.AgentRace
import com.mobispec.demo.agent.AgentStep
import com.mobispec.demo.agent.EpisodeState
import com.mobispec.demo.manifest.Scenario
import java.io.File
import kotlinx.coroutines.delay

@Composable
fun AgentScenarioScreen(sc: Scenario, ui: UiState, vm: DemoViewModel) {
    val spec = sc.agent ?: return
    val context = LocalContext.current
    var taskId by rememberSaveable(sc.id) { mutableStateOf(spec.tasks.firstOrNull()?.id ?: "") }
    var sdId by rememberSaveable(sc.id) { mutableStateOf(sc.sdMethods.firstOrNull()?.id ?: "") }
    var message by rememberSaveable(sc.id) { mutableStateOf<String?>(null) }
    val race by AgentHub.race.collectAsStateWithLifecycle()
    // a race started or opened elsewhere (adb, the HUD) selects its task
    LaunchedEffect(race?.task?.id) { race?.takeIf { it.scenarioId == sc.id }?.let { taskId = it.task.id } }
    val task = spec.tasks.firstOrNull { it.id == taskId }
    val sd = sc.sdMethods.firstOrNull { it.id == sdId }
    val ar = sd?.let { sc.ar(it.target) }
    val missing = ui.missing[sc.id].orEmpty()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(sc.subtitle, fontSize = 13.sp)
        if (!AgentHub.serviceEnabled) {
            Card { Column(Modifier.padding(12.dp)) {
                Text("접근성 서비스가 꺼져 있습니다", fontWeight = FontWeight.Bold, color = Palette.warn)
                Text("에이전트가 화면을 읽고 누르려면 설정 > 접근성 > 설치된 앱에서 MobiSpec Demo를 켜야 합니다.", fontSize = 12.sp)
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                    Text("접근성 설정 열기")
                }
            } }
        }
        Text("Task", style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(spec.tasks, key = { it.id }) { t -> FilterChip(selected = taskId == t.id, onClick = { taskId = t.id }, label = { Text(t.title) }) }
        }
        task?.let { Text("\"${it.goal}\"  (최대 ${it.maxSteps}스텝)", fontSize = 13.sp) }
        Text("Speculative method", style = MaterialTheme.typography.titleSmall)
        for (m in sc.sdMethods) MethodCard(m, selected = m.id == sdId) { sdId = m.id }
        if (missing.isNotEmpty()) Text("Missing on device: ${missing.joinToString()}", color = Palette.bad, fontSize = 12.sp)
        val running = race?.running == true
        Button(
            onClick = { message = vm.agentRace(sc, task!!, listOf(ar!!.id, sd.id)) },
            enabled = task != null && sd != null && ar != null && !running && missing.isEmpty(), modifier = Modifier.fillMaxWidth(),
        ) { Text("${ar?.label ?: "AR"}, 다음 ${sd?.label ?: ""}로 같은 작업 실행") }
        if (running) OutlinedButton(onClick = AgentHub::stop, modifier = Modifier.fillMaxWidth()) { Text("중단") }
        OutlinedButton(
            onClick = { if (!AgentHub.loadRecorded(context, sc, task!!, sd!!.id)) message = "이 작업의 ${sd.title} 기록이 아직 없다." },
            enabled = task != null && sd != null && !running, modifier = Modifier.fillMaxWidth(),
        ) { Text("기록된 실행 열기 (다시 돌리지 않고 결과와 재생만)") }
        message?.let { Text(it, color = Palette.warn, fontSize = 12.sp) }
        race?.takeIf { it.scenarioId == sc.id && it.task.id == taskId }?.let { r ->
            HorizontalDivider()
            AgentPair(r, sc, null, Modifier.fillMaxWidth().height(640.dp))
            if (!r.running && r.episodes.all { it.record != null }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.go(Screen.AgentReplay(sc.id)) }) { Text("나란히 재생") }
                    for ((i, e) in r.episodes.withIndex()) {
                        OutlinedButton(onClick = { vm.go(Screen.AgentFocus(sc.id, i)) }) { Text("${shortTitle(e.methodTitle)} 전체 화면", maxLines = 1) }
                    }
                }
            }
        }
    }
}

/** The race's runs side by side at `t` (null: as finished), each in the agent run layout scaled to its column. */
@Composable
fun AgentPair(r: AgentRace, sc: Scenario?, t: Long?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val gap = 8.dp
        val k = ((maxWidth - gap) / 2 - 20.dp) / 367.dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (e in r.episodes) {
                Surface(Modifier.weight(1f).fillMaxHeight(), color = Palette.bg, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Palette.line)) {
                    AgentRunView(e, r, sc, t, k, Modifier.padding(10.dp))
                }
            }
        }
    }
}

/** Both runs on their recorded clocks after a countdown: the one that finishes first stops while the other goes on. */
@Composable
fun AgentReplayScreen(vm: DemoViewModel) {
    val race by AgentHub.race.collectAsStateWithLifecycle()
    val r = race ?: return
    val end = r.episodes.mapNotNull { it.episodeMs }.maxOrNull() ?: return
    val clock = rememberReplayClock(end)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AgentPair(r, vm.scenario(r.scenarioId), clock.t, Modifier.fillMaxWidth().weight(1f))
            ReplayControls(clock, end)
        }
        Countdown(clock.countdown, Palette.sd)
    }
}

/** One run on the whole screen on its recorded clock, for recording; tap for the controls. */
@Composable
fun AgentFocusScreen(s: Screen.AgentFocus, vm: DemoViewModel) {
    val race by AgentHub.race.collectAsStateWithLifecycle()
    val r = race ?: return
    val e = r.episodes.getOrNull(s.index) ?: return
    val end = e.episodeMs ?: return
    val clock = rememberReplayClock(end)
    var controls by remember { mutableStateOf(false) }
    Immersive()
    Box(
        Modifier.fillMaxSize().background(Palette.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controls = !controls },
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 22.dp, vertical = 18.dp)) {
            AgentRunView(e, r, vm.scenario(r.scenarioId), clock.t, 1f, Modifier.weight(1f))
            if (controls) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { clock.restart() }) { Text("Replay ${shortTitle(e.methodTitle)}") }
                    val other = (s.index + 1) % r.episodes.size
                    OutlinedButton(onClick = { vm.go(Screen.AgentFocus(s.scenarioId, other)) }) { Text(shortTitle(r.episodes[other].methodTitle), maxLines = 1) }
                    OutlinedButton(onClick = { vm.go(Screen.AgentReplay(s.scenarioId)) }) { Text("Both") }
                }
            }
        }
        Countdown(clock.countdown, if (e.onReference) Palette.sd else Palette.ar)
    }
}

/** A recorded clock in ms that starts after a 3-2-1 countdown and stops at `end`. */
class ReplayClock {
    var t by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var speed by mutableFloatStateOf(1f)
    var countdown by mutableStateOf<Int?>(null)
    var runs by mutableIntStateOf(0)   // bumped to start over
    fun restart() { t = 0; runs++ }
}

@Composable
fun rememberReplayClock(end: Long): ReplayClock {
    val c = remember { ReplayClock() }
    LaunchedEffect(c.runs) {
        c.playing = false
        for (n in 3 downTo 1) { c.countdown = n; delay(1000) }
        c.countdown = null
        c.playing = true
    }
    LaunchedEffect(c.playing, c.speed) {
        var last = SystemClock.elapsedRealtime()
        while (c.playing && c.t < end) {
            delay(33)
            val now = SystemClock.elapsedRealtime()
            c.t = minOf(end, c.t + ((now - last) * c.speed).toLong())
            last = now
        }
        if (c.t >= end) c.playing = false
    }
    return c
}

@Composable
private fun ReplayControls(c: ReplayClock, end: Long) {
    Slider(value = c.t.toFloat(), onValueChange = { c.t = it.toLong(); c.playing = false }, valueRange = 0f..end.toFloat())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { if (c.t >= end) c.restart() else c.playing = !c.playing }) { Text(if (c.playing) "멈춤" else "재생") }
        for (x in listOf(1f, 2f, 4f)) FilterChip(selected = c.speed == x, onClick = { c.speed = x }, label = { Text("${x.toInt()}x") })
    }
}
