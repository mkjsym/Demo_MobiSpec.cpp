package com.mobispec.demo.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.mobispec.demo.manifest.Backend

data class DeviceStatus(
    val model: String,
    val soc: String,
    val batteryPct: Int,
    val batteryTempC: Double,
    val charging: Boolean,
    val thermalStatus: Int,
    val headroom: Float,     // NaN when the platform has no forecast (or it was polled too often)
    val availMemMb: Long,
    val bootCount: Int,
) {
    val thermalLabel: String get() = when (thermalStatus) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "?"
    }

    fun snapshot(): Map<String, Double> = mapOf(
        "battery_pct" to batteryPct.toDouble(),
        "battery_temp_c" to batteryTempC,
        "thermal_status" to thermalStatus.toDouble(),
        "headroom" to headroom.toDouble(),
        "avail_mem_mb" to availMemMb.toDouble(),
    )
}

class DeviceMonitor(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val activity = context.getSystemService(ActivityManager::class.java)

    fun read(): DeviceStatus {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val mem = ActivityManager.MemoryInfo().also { activity.getMemoryInfo(it) }
        return DeviceStatus(
            model = Build.MODEL,
            soc = Build.SOC_MODEL,
            batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            batteryTempC = temp,
            charging = plugged,
            thermalStatus = power.currentThermalStatus,
            headroom = power.getThermalHeadroom(0),
            availMemMb = mem.availMem / (1024 * 1024),
            bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1),
        )
    }
}

/**
 * Alternating NPU-draft and GPU-draft runs in one boot session has rebooted this phone
 * (examples/speculative-dflash-qnn-npu/README.md, "Do not mix draft backends"), so the first
 * drafter backend used after a boot is pinned until the next boot.
 */
class BootLock(context: Context) {
    private val prefs = context.getSharedPreferences("boot_lock", Context.MODE_PRIVATE)

    fun pinned(bootCount: Int): Backend? {
        if (prefs.getInt("boot", -2) != bootCount) return null
        return prefs.getString("drafter", null)?.let { Backend.valueOf(it) }
    }

    fun allows(bootCount: Int, drafter: Backend): Boolean = pinned(bootCount).let { it == null || it == drafter }

    fun pin(bootCount: Int, drafter: Backend) {
        if (pinned(bootCount) == null) prefs.edit().putInt("boot", bootCount).putString("drafter", drafter.name).apply()
    }

    fun reset() { prefs.edit().clear().apply() }
}
