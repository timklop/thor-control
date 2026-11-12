package com.thorctl.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import com.thorctl.ui.HudIcon
import com.thorctl.ui.HudMessage
import com.thorctl.ui.HudPill
import com.thorctl.ui.MenuOverlay
import com.thorctl.ui.ThorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Shows the menu on the bottom display.
 *
 * Preferred window type is TYPE_ACCESSIBILITY_OVERLAY (via our accessibility service's display
 * context): it is above all apps, the status bar and the IME, and cannot be hidden by apps that
 * use HIDE_OVERLAY_WINDOWS. If the service is not bound we fall back to TYPE_APPLICATION_OVERLAY.
 */
@SuppressLint("StaticFieldLeak")
object OverlayController {
    private const val TAG = "ThorOverlay"

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var a11y: AccessibilityService? = null
    private var view: ComposeView? = null
    private var dialog: Dialog? = null
    private var owner: OverlayOwner? = null

    /** Drives the enter/exit animation; the window is removed after the exit animation. */
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible

    private val _mode = MutableStateFlow("none")
    /** "accessibility", "application" or "none" — shown on the status screen. */
    val mode: StateFlow<String> = _mode

    private val _a11yBound = MutableStateFlow(false)
    val a11yBound: StateFlow<Boolean> = _a11yBound

    fun init(context: Context) {
        app = context.applicationContext
        DaemonClient.onButton = { type -> if (type == "short") toggle() }
        DaemonClient.onHud = { o -> showHud(toHud(o)) }
        scope.launch {
            DaemonClient.state.collect { s ->
                if (s != null && !s.bottomOn) removeNow()
            }
        }
    }

    fun onAccessibilityConnected(service: AccessibilityService) {
        a11y = service
        _a11yBound.value = true
        Log.i(TAG, "accessibility service connected")
        // Re-host an open menu in the stronger window layer.
        if (view != null && _mode.value != "accessibility") {
            removeNow()
            show()
        }
    }

    fun onAccessibilityDisconnected(service: AccessibilityService) {
        if (a11y === service) {
            a11y = null
            _a11yBound.value = false
            if (_mode.value == "accessibility") removeNow()
        }
    }

    val isShowing: Boolean get() = view != null && _visible.value

    fun toggle() {
        if (isShowing) hide() else show()
    }

    fun bottomDisplay(): Display? {
        val dm = app.getSystemService(DisplayManager::class.java)
        val wanted = DaemonClient.state.value?.bottomDisplayId ?: -1
        if (wanted >= 0) dm.getDisplay(wanted)?.let { return it }
        // Fallback: the smaller of the built-in panels that is not the default display.
        return dm.displays
            .filter { it.displayId != Display.DEFAULT_DISPLAY }
            .minByOrNull { it.mode.physicalWidth * it.mode.physicalHeight }
    }

    fun show() {
        if (!::app.isInitialized) return
        if (view != null) {
            _visible.value = true
            return
        }
        if (DaemonClient.state.value?.bottomOn == false) return
        val display = bottomDisplay() ?: run {
            Log.w(TAG, "no bottom display")
            return
        }
        val service = a11y
        val attempts = buildList {
            if (service != null) add("accessibility")
            if (Settings.canDrawOverlays(app)) add("application")
        }
        // Visible before the first composition so the entrance animation (not the exit) runs.
        _visible.value = true
        for (m in attempts) {
            if (tryAdd(display, m, service)) {
                _mode.value = m
                DaemonClient.subscribeStats(true)
                return
            }
        }
        _visible.value = false
        _mode.value = "none"
        Log.w(TAG, "could not show overlay: no accessibility service and no overlay permission")
    }

    /**
     * The menu is hosted in a Dialog window (still TYPE_ACCESSIBILITY_OVERLAY via the service's
     * display context). Dialog windows have a DecorView, which gives us the public
     * Window.setBackgroundBlurRadius() blur-region path. That path works on the secondary
     * (bottom) display, unlike FLAG_BLUR_BEHIND, and animating it needs no window relayout.
     */
    private fun tryAdd(display: Display, m: String, service: AccessibilityService?): Boolean {
        val type: Int
        val ctx: Context
        if (m == "accessibility" && service != null) {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            ctx = service.createDisplayContext(display)
        } else {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            ctx = app.createDisplayContext(display).createWindowContext(type, null)
        }
        val blur = ctx.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled
        val d = Dialog(ctx, com.thorctl.R.style.Theme_ThorControl_Menu)
        val w = d.window ?: return false
        w.setType(type)
        w.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        w.setFormat(PixelFormat.TRANSLUCENT)
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // Not focusable: tapping the menu must not steal window focus from the game on top.
        w.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        )
        w.attributes = w.attributes.apply {
            title = "ThorControlMenu"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            windowAnimations = 0
        }
        w.setBackgroundBlurRadius(0)

        val o = OverlayOwner()
        val v = ComposeView(ctx)
        o.attach(v)
        v.setContent {
            ThorTheme {
                MenuOverlay(
                    visible = _visible,
                    blur = blur,
                    onReveal = { p -> if (blur) setBlur(d, p) },
                    onExitFinished = { removeNow() },
                    onOpenApp = { openApp() },
                )
            }
        }
        d.setContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        d.setCancelable(false)
        return try {
            d.show()
            o.resume()
            dialog = d
            view = v
            owner = o
            Log.i(TAG, "menu shown on display ${display.displayId} as $m (blur=$blur)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "show menu as $m failed", e)
            o.destroy()
            false
        }
    }

    fun hide() {
        _visible.value = false
    }

    /** Max backdrop blur, in px, reached at the end of the reveal animation. */
    private const val MAX_BLUR_PX = 150
    private var lastBlur = -1

    private fun setBlur(d: Dialog, progress: Float) {
        if (dialog != null && dialog !== d) return
        val r = (progress.coerceIn(0f, 1f) * MAX_BLUR_PX).toInt()
        if (r == lastBlur) return
        lastBlur = r
        d.window?.setBackgroundBlurRadius(r)
    }

    fun removeNow() {
        val d = dialog ?: return
        dialog = null
        view = null
        lastBlur = -1
        try {
            d.dismiss()
        } catch (e: Exception) {
            Log.w(TAG, "dismiss", e)
        }
        owner?.destroy()
        owner = null
        _visible.value = false
        DaemonClient.subscribeStats(false)
    }

    private fun openApp() {
        hide()
        val i = android.content.Intent(app, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = android.app.ActivityOptions.makeBasic()
        DaemonClient.state.value?.topDisplayId?.takeIf { it >= 0 }?.let { opts.launchDisplayId = it }
        try {
            app.startActivity(i, opts.toBundle())
        } catch (e: Exception) {
            Log.w(TAG, "open app", e)
        }
    }

    fun haptic(effect: Int = VibrationEffect.EFFECT_HEAVY_CLICK) {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
                ?.vibrate(VibrationEffect.createPredefined(effect))
        } catch (_: Exception) {
        }
    }

    // ---- HUD: short confirmation pill for AYN shortcuts ------------------------------------------

    private var hudView: ComposeView? = null
    private var hudWm: WindowManager? = null
    private var hudOwner: OverlayOwner? = null
    private var hudDisplayId = -1
    private val _hud = MutableStateFlow<HudMessage?>(null)
    private val hudHide = Runnable { _hud.value = null }

    private fun toHud(o: org.json.JSONObject): HudMessage {
        val display = o.optInt("display", Display.DEFAULT_DISPLAY)
        fun screen(v: String) = if (v == com.thorctl.shared.Proto.TARGET_BOTTOM) "bottom screen" else "top screen"
        return when (o.optString("kind")) {
            "controller" -> HudMessage(
                HudIcon.CONTROLLER,
                when (o.optString("target")) {
                    com.thorctl.shared.Proto.TARGET_FREE -> "Controller follows focus"
                    else -> "Controller → ${screen(o.optString("target"))}"
                },
                display,
            )
            "moved" -> {
                val to = o.optString("to")
                HudMessage(
                    if (to == com.thorctl.shared.Proto.TARGET_BOTTOM) HudIcon.MOVE_DOWN else HudIcon.MOVE_UP,
                    "${appLabel(o.optString("pkg"))} → ${screen(to)}",
                    display,
                )
            }
            "brightness" -> HudMessage(
                HudIcon.BRIGHTNESS,
                if (o.optString("which") == "bottom") "Bottom screen brightness" else "Top screen brightness",
                display,
                level = o.optInt("pct") / 100f,
            )
            "nothing" -> HudMessage(HudIcon.INFO, "No app on the ${screen(o.optString("from"))}", display)
            "game" -> HudMessage(
                HudIcon.GAME,
                when (o.optString("state")) {
                    "on" -> "Game mode on"
                    "off" -> "Game mode off"
                    "bottom_off" -> "Bottom screen off · AYN turns it on"
                    else -> {
                        val freed = o.optInt("freedMb")
                        "Game mode · ${appLabel(o.optString("pkg"))}" + if (freed >= 100) " · ${"%.1f".format(freed / 1024f)} GB freed" else ""
                    }
                },
                display,
            )
            else -> HudMessage(HudIcon.INFO, o.optString("text"), display)
        }
    }

    private fun appLabel(pkg: String): String = try {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg.ifBlank { "App" }
    }

    private fun showHud(msg: HudMessage) {
        if (!::app.isInitialized) return
        val dm = app.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(msg.displayId) ?: dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        if (hudView != null && hudDisplayId != display.displayId) removeHud()
        if (hudView == null && !addHud(display)) return
        haptic(VibrationEffect.EFFECT_TICK)
        _hud.value = msg
        main.removeCallbacks(hudHide)
        main.postDelayed(hudHide, 1800)
    }

    private fun addHud(display: Display): Boolean {
        val service = a11y
        val type: Int
        val ctx: Context
        if (service != null) {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            ctx = service.createDisplayContext(display)
        } else if (Settings.canDrawOverlays(app)) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            ctx = app.createDisplayContext(display).createWindowContext(type, null)
        } else {
            return false
        }
        val windowManager = ctx.getSystemService(WindowManager::class.java)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "ThorControlHud"
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            y = (28 * ctx.resources.displayMetrics.density).toInt()
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }
        val o = OverlayOwner()
        val v = ComposeView(ctx)
        o.attach(v)
        v.setContent { ThorTheme { HudPill(_hud, onGone = { removeHud() }) } }
        return try {
            windowManager.addView(v, lp)
            o.resume()
            hudView = v
            hudWm = windowManager
            hudOwner = o
            hudDisplayId = display.displayId
            true
        } catch (e: Exception) {
            Log.w(TAG, "add HUD failed", e)
            o.destroy()
            false
        }
    }

    private fun removeHud() {
        val v = hudView ?: return
        hudView = null
        try {
            hudWm?.removeViewImmediate(v)
        } catch (_: Exception) {
        }
        hudOwner?.destroy()
        hudOwner = null
        hudWm = null
        hudDisplayId = -1
    }

    fun post(r: () -> Unit) {
        main.post(r)
    }

    /**
     * Context and window type for an overlay on [display]: an accessibility overlay when the
     * service is bound (above everything), else an application overlay. Null if neither works.
     */
    fun overlayContext(display: Display): Pair<Context, Int>? {
        if (!::app.isInitialized) return null
        val service = a11y
        return when {
            service != null -> service.createDisplayContext(display) to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Settings.canDrawOverlays(app) -> {
                val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                app.createDisplayContext(display).createWindowContext(type, null) to type
            }
            else -> null
        }
    }

    val appContext: Context get() = app
}
