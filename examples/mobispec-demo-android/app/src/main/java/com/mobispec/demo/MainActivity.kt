package com.mobispec.demo

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.em
import com.mobispec.demo.ui.DemoApp
import com.mobispec.demo.ui.DemoViewModel
import com.mobispec.demo.ui.Palette

class MainActivity : ComponentActivity() {
    private val vm: DemoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // a run is a few seconds to a minute of unattended decoding; the screen must not lock mid-demo
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // behind the keyguard the app is cached and the platform freezer stops the driver with it
        // (do_freezer_trap); starting the activity therefore wakes the screen and shows over the lock screen
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            MaterialTheme(colorScheme = scheme, typography = typography) {
                Surface(color = Palette.bg) { DemoApp(vm) }
            }
        }
        if (savedInstanceState == null) autorun(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        autorun(intent)
    }

    // adb shell am start -n com.mobispec.demo/.MainActivity --es scenario <id> [--es prompt <id>] --es methods <id,id> [--ez race true]
    // (--ez open true only opens the scenario screen; --ez recorded true opens the latest recorded race instead of running,
    // --es focus ar|sd|auto shows one run full screen, --ez replay true replays the recorded race)
    // --ez chart false|true hides or shows the tokens-per-call bars, --ez stats true|false the tokens, time and progress
    // rows; each stays set, and works without a scenario too
    private fun autorun(intent: Intent) {
        // the agent service brings the app back when a race ends
        if (intent.getBooleanExtra("agent_result", false)) {
            com.mobispec.demo.agent.AgentHub.race.value?.let { vm.go(com.mobispec.demo.ui.Screen.ScenarioScreen(it.scenarioId)) }
            return
        }
        if (intent.hasExtra("chart")) vm.settings(vm.ui.value.settings.copy(stepChart = intent.getBooleanExtra("chart", true)))
        if (intent.hasExtra("stats")) vm.settings(vm.ui.value.settings.copy(stats = intent.getBooleanExtra("stats", false)))
        val scenario = intent.getStringExtra("scenario") ?: return
        val methods = intent.getStringExtra("methods")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        vm.autorun(scenario, intent.getStringExtra("prompt"), methods, intent.getBooleanExtra("race", false), intent.getBooleanExtra("open", false),
            recorded = intent.getBooleanExtra("recorded", false), focus = intent.getStringExtra("focus"), replay = intent.getBooleanExtra("replay", false))
    }

    override fun onResume() {
        super.onResume()
        vm.reload()
    }
}

private val scheme = darkColorScheme(
    primary = Palette.sd, onPrimary = Palette.bg, secondary = Palette.ar, onSecondary = Palette.bg,
    background = Palette.bg, onBackground = Palette.text, surface = Palette.bg, onSurface = Palette.text,
    surfaceVariant = Palette.panel, onSurfaceVariant = Palette.muted, outline = Palette.line, outlineVariant = Palette.line,
    surfaceContainerLowest = Palette.bg, surfaceContainerLow = Palette.panel, surfaceContainer = Palette.panel,
    surfaceContainerHigh = Color(0xFF18212B), surfaceContainerHighest = Color(0xFF1C2631),
)

// line height in proportion to the size, so 11-13 sp text set on the default body style is not double spaced
private val typography = Typography().run {
    copy(
        bodyLarge = bodyLarge.copy(lineHeight = 1.35.em), bodyMedium = bodyMedium.copy(lineHeight = 1.35.em),
        bodySmall = bodySmall.copy(lineHeight = 1.35.em),
    )
}
