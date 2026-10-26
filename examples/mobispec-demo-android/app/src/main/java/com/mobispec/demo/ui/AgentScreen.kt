package com.mobispec.demo.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Image
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
import java.io.File

private val VISION = Color(0xFF7E57C2)
private val PREFILL = Color(0xFF8D6E63)
private val ACT = Color(0xFF546E7A)

@Composable
fun AgentScenarioScreen(sc: Scenario, ui: UiState, vm: DemoViewModel) {
    val spec = sc.agent ?: return
    val context = LocalContext.current
    var taskId by rememberSaveable(sc.id) { mutableStateOf(spec.tasks.firstOrNull()?.id ?: "") }
    var sdId by rememberSaveable(sc.id) { mutableStateOf(sc.sdMethods.firstOrNull()?.id ?: "") }
    var message by rememberSaveable(sc.id) { mutableStateOf<String?>(null) }
    val race by AgentHub.race.collectAsStateWithLifecycle()
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
        ) { Text("${ar?.title ?: "AR"}, 다음 ${sd?.title ?: ""}로 같은 작업 실행") }
        if (running) OutlinedButton(onClick = AgentHub::stop, modifier = Modifier.fillMaxWidth()) { Text("중단") }
        message?.let { Text(it, color = Palette.warn, fontSize = 12.sp) }
        race?.takeIf { it.scenarioId == sc.id }?.let { HorizontalDivider(); AgentRaceCard(it) { vm.go(Screen.AgentReplay(sc.id)) } }
    }
}

@Composable
fun AgentRaceCard(r: AgentRace, onReplay: () -> Unit) {
    val a = r.episodes.first()
    val s = r.episodes.last()
    Text(r.task.title, style = MaterialTheme.typography.titleMedium)
    if (r.episodes.size > 1 && a.episodeMs != null && s.episodeMs != null) {
        val same = sameActions(a, s)
        Card { Column(Modifier.padding(12.dp)) {
            Text("작업 시간 ${sec(a.episodeMs)} -> ${sec(s.episodeMs)}  (%.2f배)".format(a.episodeMs.toDouble() / s.episodeMs),
                fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Palette.sd)
            Text("decode ${sec(a.steps.sumOf { it.decodeMs }.toLong())} -> ${sec(s.steps.sumOf { it.decodeMs }.toLong())}, " +
                "같은 시간이 드는 비전, prefill, 동작 ${sec((a.episodeMs - a.steps.sumOf { it.decodeMs }).toLong())}", fontSize = 12.sp)
            val tokensSame = s.steps.isNotEmpty() && s.steps.all { it.same == true }
            Text(when {
                s.onReference && tokensSame -> "같은 화면 입력에서 ${s.steps.size}스텝 모두 AR과 토큰까지 같다"
                same == null -> "두 실행이 같은 행동을 했다 (${a.steps.size}스텝)"
                else -> "행동이 스텝 $same 에서 달라졌다"
            }, color = if (same == null) Palette.ok else Palette.bad, fontSize = 12.sp)
            if (s.onReference) Text("SD 실행은 화면을 직접 관측하고 조작하지만, 모델 입력은 AR 실행의 같은 스텝 화면이다(렌더링 차이로 갈라지지 않게).",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
    if (!r.running && r.episodes.size > 1 && r.episodes.all { it.record != null }) {
        OutlinedButton(onClick = { onReplay() }, modifier = Modifier.fillMaxWidth()) { Text("나란히 재생 (기록된 시간 그대로)") }
    }
    for (e in r.episodes) EpisodeCard(e, scale = r.episodes.mapNotNull { it.episodeMs }.maxOrNull())
}

/** Both runs' live screens on their recorded clocks: the one that finishes first stops while the other goes on. */
@Composable
fun AgentReplayScreen() {
    val race by AgentHub.race.collectAsStateWithLifecycle()
    val r = race ?: return
    val runs = r.episodes.filter { it.record != null }
    val end = runs.mapNotNull { it.episodeMs }.maxOrNull() ?: return
    var t by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(playing, speed) {
        var last = SystemClock.elapsedRealtime()
        while (playing && t < end) {
            delay(50)
            val now = SystemClock.elapsedRealtime()
            t = minOf(end, t + ((now - last) * speed).toLong())
            last = now
        }
        if (t >= end) playing = false
    }
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(r.task.title + "  -  %.1f s".format(t / 1000.0), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (e in runs) ReplayPane(e, t, Modifier.weight(1f))
        }
        Slider(value = t.toFloat(), onValueChange = { t = it.toLong(); playing = false }, valueRange = 0f..end.toFloat())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (t >= end) t = 0; playing = !playing }) { Text(if (playing) "멈춤" else "재생") }
            for (x in listOf(1f, 2f, 4f)) FilterChip(selected = speed == x, onClick = { speed = x }, label = { Text("${x.toInt()}x") })
        }
    }
}

@Composable
private fun ReplayPane(e: EpisodeState, t: Long, modifier: Modifier) {
    val dir = e.record!!.parentFile!!
    val done = e.episodeMs != null && t >= e.episodeMs
    val k = e.steps.indexOfFirst { it.endMs > t }.let { if (it < 0) e.steps.size - 1 else it }   // the step under way at t
    val file = File(dir, "step%02d.png".format(k + 1))
    val bitmap = remember(file.path) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // fixed-height headers, so both screenshots start on the same line
        Text(e.methodTitle, fontWeight = FontWeight.Bold, color = if (e.onReference) Palette.sd else Palette.ar, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text(if (done) "완료 - ${e.steps.size}스텝 - ${sec(e.episodeMs!!)}" else "스텝 ${k + 1}/${e.steps.size} - ${e.steps.getOrNull(k)?.action ?: ""}",
            fontSize = 11.sp, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (done) Palette.ok else Color.Unspecified)
        bitmap?.let { Image(it, null, Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit) }
    }
}

@Composable
private fun EpisodeCard(e: EpisodeState, scale: Long?) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val verdict = when (e.success) { true -> "성공"; false -> "실패"; null -> e.phase }
        Text("${e.methodTitle}  -  $verdict  -  ${e.steps.size}스텝  -  ${e.episodeMs?.let(::sec) ?: "-"}", fontWeight = FontWeight.Bold,
            color = when (e.success) { true -> Palette.ok; false -> Palette.bad; null -> Color.Unspecified })
        e.answer?.let { Text("답: $it", fontSize = 12.sp) }
        e.error?.let { Text(it, color = Palette.bad, fontSize = 12.sp) }
        val tokens = e.steps.sumOf { it.tokens }
        val decode = e.steps.sumOf { it.decodeMs }
        if (tokens > 0) Text("decode %.1f tok/s, AL %.2f, %d tok".format(tokens / (decode / 1000), e.steps.map { it.al }.average(), tokens), fontSize = 12.sp)
        // per step: vision, the rest of the first-token wait (prefill), decode, observe + act
        val total = (scale ?: e.episodeMs ?: 1L).toFloat()
        Row(Modifier.fillMaxWidth().height(14.dp)) {
            for (st in e.steps) for ((ms, c) in parts(st)) {
                if (ms > 0) Box(Modifier.fillMaxHeight().weight((ms / total).coerceAtLeast(0.0001f)).background(c))
            }
            val rest = total - e.steps.sumOf { parts(it).sumOf { p -> p.first.toDouble() } }.toFloat()
            if (rest > 0) Box(Modifier.fillMaxHeight().weight(rest / total))
        }
        for (st in e.steps) Text("${st.n}. ${st.action}" + (st.error?.let { "  [$it]" } ?: ""), fontSize = 11.sp, maxLines = 1)
    } }
    Text("보라: 비전  갈색: prefill  파랑: decode  회색: 관측과 동작", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun parts(s: AgentStep): List<Pair<Float, Color>> = listOf(
    s.visionMs.toFloat() to VISION, (s.prefillMs - s.visionMs).toFloat().coerceAtLeast(0f) to PREFILL,
    s.decodeMs.toFloat() to Palette.sd, (s.observeMs + s.actMs).toFloat() to ACT,
)

/** First step (1-based) whose action differs, or null when both runs did the same thing. */
private fun sameActions(a: EpisodeState, b: EpisodeState): Int? {
    val n = maxOf(a.steps.size, b.steps.size)
    return (0 until n).firstOrNull { a.steps.getOrNull(it)?.action != b.steps.getOrNull(it)?.action }?.plus(1)
}

private fun sec(ms: Long) = "%.1f s".format(ms / 1000.0)
