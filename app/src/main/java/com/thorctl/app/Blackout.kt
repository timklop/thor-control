package com.thorctl.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Gamepad mode with the screens dark: a black window over the whole bottom screen. That panel
 * has to stay powered (the controller's driver cuts the controller off once both panels are
 * off), but black OLED pixels give no light, use next to no power and can't burn in. The daemon
 * switches the top panel off and touch off on both meanwhile.
 */
@SuppressLint("StaticFieldLeak")
object Blackout {
    private const val TAG = "ThorBlackout"

    private var view: View? = null
    private var wm: WindowManager? = null

    fun init() {
        MainScope().launch {
            DaemonClient.state.collect { s ->
                val g = s?.gamepad
                set(g != null && g.on && g.dark)
            }
        }
    }

    private fun set(on: Boolean) {
        if (on == (view != null)) return
        if (on) show() else hide()
    }

    private fun show() {
        val display = OverlayController.bottomDisplay() ?: return
        val (ctx, type) = OverlayController.overlayContext(display) ?: return
        val v = View(ctx).apply { setBackgroundColor(Color.BLACK) }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            title = "ThorControlBlackout"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val w = ctx.getSystemService(WindowManager::class.java)
        try {
            w.addView(v, lp)
            view = v
            wm = w
        } catch (e: Exception) {
            Log.w(TAG, "black window failed", e)
        }
    }

    private fun hide() {
        val v = view ?: return
        try {
            wm?.removeView(v)
        } catch (_: Exception) {
        }
        view = null
        wm = null
    }
}
