package com.thorctl.app

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo

/**
 * The system keyboard service. Android always puts a keyboard window on the screen of the text
 * field being edited; ours is empty and zero-height there (apps are neither covered nor resized),
 * and the real keyboard, with its touchpad, is drawn on the bottom screen by [KeyboardController].
 * Both live in this persistent process, so keys go straight into [getCurrentInputConnection].
 */
class ThorKeyboard : InputMethodService() {

    override fun onCreate() {
        super.onCreate()
        KeyboardController.attach(this)
    }

    override fun onDestroy() {
        KeyboardController.detach(this)
        super.onDestroy()
    }

    override fun onCreateInputView(): View =
        View(this).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0) }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /** Takes no space and no touches on the editing screen. */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
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
        KeyboardController.onShow(info)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        KeyboardController.onHide()
        super.onFinishInputView(finishingInput)
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
