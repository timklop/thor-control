package com.thorctl.app

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.thorctl.ui.InlineKeyboard
import com.thorctl.ui.ThorTheme

/**
 * The system keyboard service. Android always puts a keyboard window on the screen of the text
 * field being edited. Normally ours is empty and zero-height there (apps are neither covered nor
 * resized) and the real keyboard, with its touchpad, is drawn on the bottom screen by
 * [KeyboardController]. Held upright (the top screen in portrait, where the bottom screen is off
 * to the side), the keys appear in this window instead, as an ordinary keyboard under the app.
 * Both live in this persistent process, so keys go straight into [getCurrentInputConnection].
 */
class ThorKeyboard : InputMethodService() {
    private var root: FrameLayout? = null
    private var inlineView: ComposeView? = null
    private var owner: OverlayOwner? = null

    /** Keys are in this window (top screen, portrait). */
    private var inline = false

    override fun onCreate() {
        super.onCreate()
        KeyboardController.attach(this)
    }

    override fun onDestroy() {
        dropInline()
        KeyboardController.detach(this)
        super.onDestroy()
    }

    /** Called again after every configuration change (rotation), which also restarts the view. */
    override fun onCreateInputView(): View {
        dropInline()
        return FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }.also { root = it }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /** Bottom-screen mode: takes no space and no touches on the editing screen. */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (inline) return
        val h = window?.window?.decorView?.height ?: 0
        outInsets.contentTopInsets = h
        outInsets.visibleTopInsets = h
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.setEmpty()
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        KeyboardController.onStartInput(attribute)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inline = uprightTopScreen()
        if (inline) showInline() else dropInline()
        KeyboardController.onShow(info, inline)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        KeyboardController.onHide()
        super.onFinishInputView(finishingInput)
    }

    /** This keyboard window is on the top screen and that screen is in portrait. */
    private fun uprightTopScreen(): Boolean {
        val d = try {
            display
        } catch (_: Exception) {
            null
        } ?: return false
        return d.displayId == Display.DEFAULT_DISPLAY &&
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }

    private fun showInline() {
        val r = root ?: return
        if (inlineView != null) return
        val o = OverlayOwner()
        val v = ComposeView(this)
        o.attach(v)
        // Compose looks for its lifecycle from the window's root view as well.
        window?.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(o)
            decor.setViewTreeSavedStateRegistryOwner(o)
            decor.setViewTreeViewModelStoreOwner(o)
        }
        v.setContent { ThorTheme { InlineKeyboard(KeyboardController.ui) } }
        r.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        o.resume()
        inlineView = v
        owner = o
    }

    private fun dropInline() {
        inlineView?.let { root?.removeView(it) }
        inlineView = null
        owner?.destroy()
        owner = null
    }

    /**
     * L1 / R1 are shift while typing. The daemon reads them from the controller (whichever screen
     * the controller is routed to); when they also reach us, swallow them so the app doesn't react.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (isShoulder(keyCode) && KeyboardController.isShown) true else super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (isShoulder(keyCode) && KeyboardController.isShown) true else super.onKeyUp(keyCode, event)

    private fun isShoulder(code: Int) = code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        KeyboardController.onSelection(newSelStart, newSelEnd)
    }
}
