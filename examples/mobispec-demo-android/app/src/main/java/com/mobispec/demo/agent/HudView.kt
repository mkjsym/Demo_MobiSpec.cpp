package com.mobispec.demo.agent

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** What the agent HUD shows. Plain values: an action's text never goes through a format string ("79%" once crashed it). */
data class Hud(
    val method: String,
    val sd: Boolean,
    val task: String,
    val elapsedSec: Double,
    val step: Int,
    val maxSteps: Int,
    val phase: String,
    val tps: Double?,     // decode rate over the finished steps
    val al: Double?,
    val detail: String,   // the reply being decoded, or the last action
    val note: String?,    // where the AR run was at this moment
)

/**
 * The band over the app the agent drives, in the race screen's colours: method badge and task clock on top, step,
 * phase and decode rate below. AR and SD bands differ only in the accent, so two recordings sit side by side.
 */
class HudView(context: Context) : LinearLayout(context) {
    private val badge = text(18f, bold = true)
    private val title = text(14f)
    private val clock = text(26f, bold = true)
    private val status = text(13f)
    private val speed = text(13f, bold = true)
    private val detail = text(12f)
    private val note = text(12f)
    private val rule = View(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.argb(238, 0x0B, 0x0F, 0x14))
        val pad = dp(14)
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, dp(10), pad, 0)
            addView(badge)
            addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10); marginEnd = dp(10) })
            addView(clock)
        }
        val mid = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(pad, dp(4), pad, 0)
            addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(speed)
        }
        badge.setPadding(dp(10), dp(2), dp(10), dp(2))
        badge.setTextColor(BG)
        title.setTextColor(TEXT)
        status.setTextColor(MUTED)
        detail.setTextColor(TEXT)
        note.setTextColor(MUTED)
        for (t in listOf(title, detail, note)) { t.maxLines = 1; t.ellipsize = TextUtils.TruncateAt.END }
        detail.ellipsize = TextUtils.TruncateAt.START   // the reply's newest words stay in view
        addView(top)
        addView(mid)
        addView(detail, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { setMargins(pad, dp(4), pad, 0) })
        addView(note, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { setMargins(pad, dp(2), pad, 0) })
        addView(rule, LayoutParams(LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(10) })
    }

    fun show(h: Hud) {
        val accent = if (h.sd) SD else AR
        badge.text = h.method
        badge.background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(8).toFloat() }
        title.text = h.task
        clock.text = "%.1f s".format(h.elapsedSec)
        clock.setTextColor(accent)
        status.text = "STEP ${h.step}/${h.maxSteps}   ${h.phase}"
        speed.text = if (h.tps != null && h.al != null) "%.1f tok/s   AL %.2f".format(h.tps, h.al) else ""
        speed.setTextColor(accent)
        detail.text = h.detail
        detail.visibility = if (h.detail.isEmpty()) GONE else VISIBLE
        note.text = h.note ?: ""
        note.visibility = if (h.note == null) GONE else VISIBLE
        rule.setBackgroundColor(accent)
    }

    private fun text(sp: Float, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        fontFeatureSettings = "tnum"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val BG = Color.rgb(0x0B, 0x0F, 0x14)
        val TEXT = Color.rgb(0xE6, 0xEB, 0xF2)
        val MUTED = Color.rgb(0x8B, 0x97, 0xA8)
        val AR = Color.rgb(0xA7, 0xB1, 0xC0)
        val SD = Color.rgb(0x4F, 0xC3, 0xF7)
    }
}
