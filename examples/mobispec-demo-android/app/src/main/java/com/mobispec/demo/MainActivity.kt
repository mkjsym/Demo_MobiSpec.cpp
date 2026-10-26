package com.mobispec.demo

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.mobispec.demo.ui.DemoApp
import com.mobispec.demo.ui.DemoViewModel

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
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF4FC3F7), secondary = Color(0xFF9AA4B2))) {
                Surface { DemoApp(vm) }
            }
        }
        if (savedInstanceState == null) autorun(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        autorun(intent)
    }

    // adb shell am start -n com.mobispec.demo/.MainActivity --es scenario <id> [--es prompt <id>] --es methods <id,id> [--ez race true]
    // (--ez open true only opens the scenario screen)
    private fun autorun(intent: Intent) {
        // the agent service brings the app back when a race ends
        if (intent.getBooleanExtra("agent_result", false)) {
            com.mobispec.demo.agent.AgentHub.race.value?.let { vm.go(com.mobispec.demo.ui.Screen.ScenarioScreen(it.scenarioId)) }
            return
        }
        val scenario = intent.getStringExtra("scenario") ?: return
        val methods = intent.getStringExtra("methods")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        vm.autorun(scenario, intent.getStringExtra("prompt"), methods, intent.getBooleanExtra("race", false), intent.getBooleanExtra("open", false))
    }

    override fun onResume() {
        super.onResume()
        vm.reload()
    }
}
