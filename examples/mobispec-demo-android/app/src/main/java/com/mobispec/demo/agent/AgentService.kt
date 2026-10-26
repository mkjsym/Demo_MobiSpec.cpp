package com.mobispec.demo.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The phone agent's eyes and hands. The screen it reads is what odbench's `uiautomator dump` gives the host probe
 * (visible nodes in pre-order, clipped bounds, the same usefulness filter), and its actions are odbench's act.py
 * done with gestures and node actions instead of `input`.
 */
class AgentService : AccessibilityService() {
    companion object {
        @Volatile var instance: AgentService? = null
            private set

        private val EDITABLE = listOf("EditText", "AutoCompleteTextView", "SearchView")

        // odbench act.APP_PACKAGES (the stock Samsung apps); anything else is matched against launcher labels
        private val ALIASES = mapOf(
            "settings" to "com.android.settings", "clock" to "com.sec.android.app.clockpackage",
            "calculator" to "com.sec.android.app.popupcalculator", "contacts" to "com.samsung.android.app.contacts",
            "camera" to "com.sec.android.app.camera", "calendar" to "com.samsung.android.calendar",
            "chrome" to "com.android.chrome", "my files" to "com.sec.android.app.myfiles", "files" to "com.sec.android.app.myfiles",
            "gallery" to "com.sec.android.gallery3d", "notes" to "com.samsung.android.app.notes",
        )
    }

    class ActionError(message: String) : Exception(message)

    data class Screen(val elements: List<Element>, val foreground: String, val windowId: Int)

    @Volatile private var lastChange = 0L
    private val main = Handler(Looper.getMainLooper())
    private var hud: TextView? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        hideHud()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != packageName) lastChange = SystemClock.uptimeMillis()
    }

    override fun onInterrupt() {}

    /**
     * The progress strip over the app the agent drives. It is an accessibility overlay that takes no touches, so the
     * agent's gestures go through it, and window screenshots of the app never contain it.
     */
    fun showHud(text: CharSequence) = main.post {
        val view = hud ?: TextView(this).apply {
            setBackgroundColor(Color.argb(210, 0, 0, 0))
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(24, 12, 24, 12)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP }
            getSystemService(WindowManager::class.java).addView(this, lp)
            hud = this
        }
        view.text = text
    }

    fun hideHud() = main.post {
        hud?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        hud = null
    }

    /** Returns once no window change has arrived for [quietMs], or after [maxMs]. */
    suspend fun settle(quietMs: Long = 400, maxMs: Long = 2500) {
        val t0 = SystemClock.uptimeMillis()
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now - lastChange >= quietMs || now - t0 >= maxMs) return
            delay(50)
        }
    }

    /**
     * The band between the status bar and the navigation bar. Elements are clipped to it and the screenshot is cropped
     * to it: an item half under the 3-button bar, tapped at its full center, hit Recents (A1, 2026-10-26).
     */
    fun band(): Rect {
        val h = resources.displayMetrics.heightPixels
        val r = Rect(0, 0, resources.displayMetrics.widthPixels, h)
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            val b = Rect().also(w::getBoundsInScreen)
            if (b.top == 0 && b.bottom < h / 4) r.top = maxOf(r.top, b.bottom)
            else if (b.top > h / 2 && b.bottom == h) r.bottom = minOf(r.bottom, b.top)
        }
        return r
    }

    fun readScreen(skip: Set<String>): Screen? {
        val root = rootInActiveWindow ?: return null
        val display = band()
        val out = mutableListOf<Element>()
        fun walk(n: AccessibilityNodeInfo) {
            val r = Rect().also(n::getBoundsInScreen)
            if (!r.intersect(display)) r.setEmpty()
            val cls = n.className?.toString()
            val e = Element(
                text = n.text?.toString()?.ifEmpty { null }, desc = n.contentDescription?.toString()?.ifEmpty { null },
                cls = cls?.ifEmpty { null }, pkg = n.packageName?.toString(),
                clickable = n.isClickable, editable = cls != null && EDITABLE.any { it in cls },
                scrollable = n.isScrollable, checked = n.isChecked, selected = n.isSelected,
                left = r.left, top = r.top, right = r.right, bottom = r.bottom,
            )
            val interactive = n.isClickable || n.isLongClickable || e.editable || n.isScrollable || n.isCheckable
            if (r.width() > 0 && r.height() > 0 && (interactive || e.text != null || e.desc != null) && e.pkg !in skip) out += e
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                if (c.isVisibleToUser) walk(c)   // uiautomator drops invisible children with their subtrees
            }
        }
        walk(root)
        val foreground = out.mapNotNull { it.pkg }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "unknown"
        return Screen(out, foreground, root.windowId)
    }

    /** The given window only, so the HUD overlay never reaches the model. */
    suspend fun screenshot(windowId: Int): Bitmap? = suspendCancellableCoroutine { c ->
        takeScreenshotOfWindow(windowId, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                result.hardwareBuffer.close()
                c.resume(copy)
            }

            override fun onFailure(errorCode: Int) = c.resume(null)
        })
    }

    /**
     * The whole display, with the HUD hidden for the frame. For windows that refuse a window screenshot (the battery page
     * failed with one, A2 2026-10-26).
     */
    suspend fun screenshotDisplay(): Bitmap? {
        main.post { hud?.visibility = android.view.View.INVISIBLE }
        delay(120)   // a frame or two, so the capture no longer shows the strip
        val shot: Bitmap? = suspendCancellableCoroutine { c ->
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    c.resume(copy)
                }

                override fun onFailure(errorCode: Int) = c.resume(null)
            })
        }
        main.post { hud?.visibility = android.view.View.VISIBLE }
        return shot
    }

    /** Performs one action; returns a terminal goal status ("complete", "infeasible", "answer") or null to go on. */
    suspend fun perform(a: AgentAction, elements: List<Element>): String? {
        val metrics = resources.displayMetrics
        when (a.type) {
            "status" -> return a.goalStatus ?: "complete"
            "answer" -> return "answer"
            "click" -> tap(target(a, elements), 50)
            "long_press" -> tap(target(a, elements), 1000)
            "input_text" -> {
                tap(target(a, elements), 50)
                delay(800)
                val field = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: throw ActionError("no focused input field after tapping element ${a.index}")
                val old = if (field.isShowingHintText) "" else field.text?.toString() ?: ""
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, old + (a.text ?: ""))
                }
                if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw ActionError("the field refused the text")
            }
            "keyboard_enter" -> {
                val field = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: throw ActionError("no focused input field")
                field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            "navigate_home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "navigate_back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "scroll" -> {
                // odbench's convention: the direction the content moves, so "down" is an upward swipe
                val cx = metrics.widthPixels / 2f
                val cy = metrics.heightPixels / 2f
                val d = minOf(metrics.widthPixels, metrics.heightPixels) * 0.5f / 2
                val (x0, y0, x1, y1) = when (a.direction?.lowercase()) {
                    "down" -> listOf(cx, cy + d, cx, cy - d)
                    "up" -> listOf(cx, cy - d, cx, cy + d)
                    "left" -> listOf(cx - d, cy, cx + d, cy)
                    "right" -> listOf(cx + d, cy, cx - d, cy)
                    else -> throw ActionError("unknown scroll direction ${a.direction}")
                }
                gesture(Path().apply { moveTo(x0, y0); lineTo(x1, y1) }, 400)
            }
            "open_app" -> {
                val pkg = resolvePackage(a.appName ?: "") ?: throw ActionError("no installed app matches ${a.appName}")
                startApp(pkg)
                delay(1200)
                if (rootInActiveWindow?.packageName?.toString() != pkg) throw ActionError("$pkg did not come to the foreground")
                return null
            }
            "wait" -> delay(2000)
            else -> throw ActionError("unsupported action_type ${a.type}")
        }
        delay(300)
        settle()
        return null
    }

    /** Opens [pkg] at its launcher activity with a fresh task, so every episode starts from the same screen. */
    fun startApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: throw ActionError("$pkg has no launcher activity")
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    fun resolvePackage(name: String): String? {
        val key = name.lowercase().trim()
        if (key.isEmpty()) return null
        fun flat(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val launchable = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        ).associate { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
        ALIASES[key]?.takeIf { it in launchable }?.let { return it }
        if (key in launchable) return key
        // exact label or alias first, then the longest containment ('calendar' is inside 'simplecalendarpro')
        val names = ALIASES.entries.filter { it.value in launchable }.map { it.key to it.value } +
            launchable.map { (pkg, label) -> label to pkg }
        val f = flat(key)
        return names.mapNotNull { (n, pkg) ->
            val nf = flat(n)
            when {
                nf.isEmpty() -> null
                nf == f -> pkg to 1000 + nf.length
                nf in f || f in nf -> pkg to minOf(nf.length, f.length)
                else -> null
            }
        }.maxByOrNull { it.second }?.first
    }

    private fun target(a: AgentAction, elements: List<Element>): Pair<Float, Float> {
        val i = a.index ?: throw ActionError("action has no index")
        val e = elements.getOrNull(i) ?: throw ActionError("index $i out of range (0..${elements.size - 1})")
        return (e.left + e.right) / 2f to (e.top + e.bottom) / 2f
    }

    private suspend fun tap(p: Pair<Float, Float>, ms: Long) {
        if (!gesture(Path().apply { moveTo(p.first, p.second) }, ms)) throw ActionError("the tap was cancelled")
    }

    private suspend fun gesture(path: Path, ms: Long): Boolean = suspendCancellableCoroutine { c ->
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription) = c.resume(true)
            override fun onCancelled(d: GestureDescription) = c.resume(false)
        }, null)
        if (!sent) c.resume(false)
    }
}
