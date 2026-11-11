package com.thorctl.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.text.InputType
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.ComposeView
import com.thorctl.ui.KeyboardPanel
import com.thorctl.ui.ThorTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Thor Keyboard's logic and its bottom-screen window. [ThorKeyboard] (the system keyboard
 * service) reports when typing starts and stops; this shows the keyboard on the bottom screen
 * whichever screen the text field is on, and turns key presses, touchpad swipes and the
 * select / copy / paste buttons into edits through the service's input connection.
 */
@SuppressLint("StaticFieldLeak")
object KeyboardController {
    private const val TAG = "ThorKeyboard"

    enum class Layer { LETTERS, SYMBOLS, MORE }
    enum class Enter { RETURN, GO, SEARCH, SEND, NEXT, DONE }

    @Immutable
    data class Ui(
        val layer: Layer = Layer.LETTERS,
        val shift: Boolean = false,
        val caps: Boolean = false,
        val selecting: Boolean = false,
        val enter: Enter = Enter.RETURN,
        /** Text around the cursor, for the preview on the touchpad. */
        val before: String = "",
        val selected: String = "",
        val after: String = "",
        /** Short confirmation shown on the touchpad ("Copied"), or null. */
        val flash: String? = null,
        val flashId: Long = 0,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible

    private val main = Handler(Looper.getMainLooper())
    private var ime: ThorKeyboard? = null
    private var editor: EditorInfo? = null

    // Selection as the editor last reported it, and the end the touchpad moves while selecting.
    private var selStart = 0
    private var selEnd = 0
    private var anchor = 0
    private var moving = 0

    // Text snapshot taken when a swipe starts (bounds and surrogate pairs), null if unsupported.
    private var text: CharSequence? = null
    private var textOffset = 0

    private var lastShiftTap = 0L

    // ---- From the keyboard service ------------------------------------------------------------

    fun attach(s: ThorKeyboard) {
        ime = s
    }

    fun detach(s: ThorKeyboard) {
        if (ime === s) ime = null
        removeNow()
    }

    fun onStartInput(info: EditorInfo) {
        editor = info
        selStart = info.initialSelStart.coerceAtLeast(0)
        selEnd = info.initialSelEnd.coerceAtLeast(0)
    }

    fun onShow(info: EditorInfo) {
        editor = info
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val layer = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) Layer.SYMBOLS else Layer.LETTERS
        _ui.value = Ui(layer = layer, enter = enterFor(info))
        autoShift()
        refreshPreview()
        main.removeCallbacks(removeRunnable)
        show()
        DaemonClient.keyboardVisible(true)
    }

    fun onHide() {
        _visible.value = false
        DaemonClient.keyboardVisible(false)
        _ui.value = _ui.value.copy(selecting = false)
        // Switching between fields hides and shows again right away: don't rebuild the window.
        main.removeCallbacks(removeRunnable)
        main.postDelayed(removeRunnable, 400)
    }

    fun onSelection(start: Int, end: Int) {
        selStart = start
        selEnd = end
        refreshPreview()
    }

    private fun enterFor(info: EditorInfo): Enter {
        val multiLine = info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 || multiLine) return Enter.RETURN
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> Enter.GO
            EditorInfo.IME_ACTION_SEARCH -> Enter.SEARCH
            EditorInfo.IME_ACTION_SEND -> Enter.SEND
            EditorInfo.IME_ACTION_NEXT -> Enter.NEXT
            EditorInfo.IME_ACTION_DONE -> Enter.DONE
            else -> Enter.RETURN
        }
    }

    private val ic: InputConnection? get() = ime?.currentInputConnection

    private fun isPassword(): Boolean {
        val t = editor?.inputType ?: return false
        val v = t and InputType.TYPE_MASK_VARIATION
        return (t and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
            (v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) ||
            (t and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    // ---- Keys ---------------------------------------------------------------------------------

    fun type(s: String) {
        val k = ime ?: return
        val u = _ui.value
        val out = if (u.layer == Layer.LETTERS && (u.shift || u.caps)) s.uppercase() else s
        if (out.length == 1) k.sendKeyChar(out[0]) else ic?.commitText(out, 1)
        if (u.shift && !u.caps) _ui.value = _ui.value.copy(shift = false)
        endSelecting()
        autoShift()
    }

    /** Types exactly this (swipe-down characters, accents): no automatic capital. */
    fun typeExact(s: String) {
        val k = ime ?: return
        if (s.length == 1) k.sendKeyChar(s[0]) else ic?.commitText(s, 1)
        val u = _ui.value
        if (u.shift && !u.caps) _ui.value = u.copy(shift = false)
        endSelecting()
        autoShift()
    }

    /** Controller buttons forwarded by the daemon while the keyboard is up (L1 / R1 = shift). */
    fun onControllerKey(key: String) {
        if (key == "shift" && _visible.value) shift()
    }

    val isShown: Boolean get() = _visible.value

    fun space() {
        ime?.sendKeyChar(' ')
        endSelecting()
        autoShift()
    }

    fun backspace() {
        val k = ime ?: return
        val c = ic
        val raw = editor?.inputType == InputType.TYPE_NULL
        when {
            c == null || raw -> k.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            selStart != selEnd -> c.commitText("", 1)
            else -> if (!c.deleteSurroundingTextInCodePoints(1, 0)) k.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
        endSelecting()
        autoShift()
    }

    // ---- Backspace swipe: select whole words backwards, delete on release (like Gboard) --------

    /** Lengths (from the cursor back) of 1, 2, 3, … words; empty if the field can't report text. */
    private var wordLens: List<Int> = emptyList()
    private var wordBase = 0
    private var wordEnd = 0
    private var wordCount = 0

    fun beginWordDelete() {
        wordLens = emptyList()
        wordCount = 0
        val c = ic ?: return
        val et = try {
            c.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 100_000 }, 0)
        } catch (_: Exception) {
            null
        } ?: return
        val off = et.startOffset
        wordBase = minOf(et.selectionStart, et.selectionEnd) + off
        wordEnd = maxOf(et.selectionStart, et.selectionEnd) + off
        val before = et.text?.subSequence(0, (wordBase - off).coerceIn(0, et.text.length))?.toString() ?: return
        wordLens = wordsBack(before, MAX_WORDS)
    }

    /** Selects [n] words back from the cursor (0 = back to how it was). */
    fun selectWords(n: Int) {
        val k = n.coerceIn(0, wordLens.size)
        if (k == wordCount) return
        wordCount = k
        val start = wordBase - (if (k == 0) 0 else wordLens[k - 1])
        ic?.setSelection(start, wordEnd)
        OverlayController.haptic(VibrationEffect.EFFECT_TICK)
    }

    /** Finger up: delete what is selected; cancelled (or nothing selected): leave the text alone. */
    fun endWordDelete(commit: Boolean) {
        if (wordCount > 0) {
            if (commit) ic?.commitText("", 1) else ic?.setSelection(wordBase, wordEnd)
        }
        wordLens = emptyList()
        wordCount = 0
        endSelecting()
        autoShift()
    }

    /** Word boundaries going back: spaces, then a run of letters/digits or a run of punctuation. */
    private fun wordsBack(before: String, max: Int): List<Int> {
        val out = ArrayList<Int>()
        var i = before.length
        while (i > 0 && out.size < max) {
            while (i > 0 && before[i - 1].isWhitespace()) i--
            if (i > 0) {
                val word = before[i - 1].isLetterOrDigit()
                while (i > 0 && !before[i - 1].isWhitespace() && before[i - 1].isLetterOrDigit() == word) i--
            }
            out += before.length - i
        }
        return out
    }

    fun enter() {
        val k = ime ?: return
        val info = editor
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (_ui.value.enter != Enter.RETURN && action != EditorInfo.IME_ACTION_NONE) {
            ic?.performEditorAction(action)
        } else {
            k.sendKeyChar('\n')
        }
        endSelecting()
    }

    fun shift() {
        val u = _ui.value
        val now = SystemClock.uptimeMillis()
        _ui.value = when {
            u.caps -> u.copy(caps = false, shift = false)
            u.shift && now - lastShiftTap < 350 -> u.copy(caps = true, shift = false)
            else -> u.copy(shift = !u.shift)
        }
        lastShiftTap = now
    }

    fun layer(l: Layer) {
        _ui.value = _ui.value.copy(layer = l, shift = false)
    }

    fun hideKeyboard() {
        ime?.requestHideSelf(0)
    }

    /** Capital letter at the start of a sentence, when the field asks for it. */
    private fun autoShift() {
        val u = _ui.value
        if (u.caps || u.layer != Layer.LETTERS) return
        val type = editor?.inputType ?: return
        val caps = try {
            ic?.getCursorCapsMode(type) ?: 0
        } catch (_: Exception) {
            0
        }
        _ui.value = u.copy(shift = caps != 0)
    }

    // ---- Touchpad / selection -----------------------------------------------------------------

    fun beginSwipe() {
        val et = try {
            ic?.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 100_000 }, 0)
        } catch (_: Exception) {
            null
        }
        text = et?.text
        textOffset = et?.startOffset ?: 0
        if (et != null) {
            selStart = et.selectionStart + textOffset
            selEnd = et.selectionEnd + textOffset
            if (!_ui.value.selecting) moving = selEnd
        }
    }

    fun endSwipe() {
        text = null
    }

    /** Moves the cursor (or the selection's moving end) one character; true if it moved. */
    fun move(dir: Int): Boolean {
        val c = ic ?: return false
        val t = text
        val selecting = _ui.value.selecting
        if (t == null) {
            // The field can't report its text: arrow keys (with shift while selecting).
            val code = if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            val meta = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
            val now = SystemClock.uptimeMillis()
            c.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
            c.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
            return true
        }
        val len = textOffset + t.length
        val from = when {
            selecting -> moving
            dir < 0 -> selStart
            else -> selEnd
        }
        var to = (from + dir).coerceIn(0, len)
        // Never stop between the two halves of an emoji / surrogate pair.
        val i = to - textOffset
        if (i in 1 until t.length && Character.isHighSurrogate(t[i - 1]) && Character.isLowSurrogate(t[i])) to += dir
        to = to.coerceIn(0, len)
        val collapsing = !selecting && selStart != selEnd
        if (to == from && !collapsing) return false
        if (selecting) {
            moving = to
            c.setSelection(anchor, to)
            selStart = minOf(anchor, to)
            selEnd = maxOf(anchor, to)
        } else {
            val p = if (collapsing) from else to
            c.setSelection(p, p)
            selStart = p
            selEnd = p
            moving = p
        }
        OverlayController.haptic(VibrationEffect.EFFECT_TICK)
        return true
    }

    fun toggleSelect() {
        val on = !_ui.value.selecting
        if (on) {
            beginSwipe()
            endSwipe()
            anchor = selStart
            moving = selEnd
        }
        _ui.value = _ui.value.copy(selecting = on)
    }

    private fun endSelecting() {
        if (_ui.value.selecting) _ui.value = _ui.value.copy(selecting = false)
    }

    fun copy() {
        val sel = try {
            ic?.getSelectedText(0)
        } catch (_: Exception) {
            null
        }
        if (sel.isNullOrEmpty()) {
            flash("Nothing selected")
            return
        }
        if (isPassword()) {
            flash("Can't copy passwords")
            return
        }
        clipboard()?.setPrimaryClip(ClipData.newPlainText("text", sel))
        endSelecting()
        flash("Copied")
    }

    fun paste() {
        val app = OverlayController.appContext
        val clip = clipboard()?.primaryClip
        val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(app)?.toString() else null
        if (t.isNullOrEmpty()) {
            flash("Clipboard is empty")
            return
        }
        ic?.commitText(t, 1)
        endSelecting()
        autoShift()
    }

    private fun clipboard(): ClipboardManager? = OverlayController.appContext.getSystemService(ClipboardManager::class.java)

    private fun flash(msg: String) {
        _ui.value = _ui.value.copy(flash = msg, flashId = SystemClock.uptimeMillis())
    }

    /** Text around the cursor for the touchpad preview (masked in password fields). */
    private fun refreshPreview() {
        val c = ic ?: return
        try {
            var before = c.getTextBeforeCursor(PREVIEW_CHARS, 0)?.toString().orEmpty()
            var selected = c.getSelectedText(0)?.toString().orEmpty()
            var after = c.getTextAfterCursor(PREVIEW_CHARS, 0)?.toString().orEmpty()
            if (isPassword()) {
                before = "•".repeat(before.length)
                selected = "•".repeat(selected.length)
                after = "•".repeat(after.length)
            }
            if (selected.length > 40) selected = selected.take(18) + " … " + selected.takeLast(18)
            _ui.value = _ui.value.copy(before = before.substringAfterLast('\n'), selected = selected, after = after.substringBefore('\n'))
        } catch (e: Exception) {
            Log.w(TAG, "preview", e)
        }
    }

    // ---- Window on the bottom screen --------------------------------------------------------

    private var dialog: Dialog? = null
    private var owner: OverlayOwner? = null
    private val removeRunnable = Runnable { removeNow() }

    private fun bottomDisplay(): Display? {
        val dm = OverlayController.appContext.getSystemService(DisplayManager::class.java)
        val id = DaemonClient.state.value?.bottomDisplayId ?: -1
        if (id >= 0) dm.getDisplay(id)?.let { return it }
        return dm.displays.filter { it.displayId != Display.DEFAULT_DISPLAY }.minByOrNull { it.mode.physicalWidth * it.mode.physicalHeight }
    }

    private fun show() {
        // Typing needs the bottom screen: wake it if it was turned off (game mode, menu button).
        if (DaemonClient.state.value?.bottomOn == false) {
            DaemonClient.setBottomPower(true)
            main.postDelayed({ if (_visible.value) showWindow() }, 350)
            _visible.value = true
            return
        }
        _visible.value = true
        showWindow()
    }

    private fun showWindow() {
        if (dialog != null) return
        val display = bottomDisplay() ?: return
        val (ctx, type) = OverlayController.overlayContext(display) ?: run {
            Log.w(TAG, "no way to show the keyboard overlay")
            return
        }
        val d = Dialog(ctx, com.thorctl.R.style.Theme_ThorControl_Menu)
        val w = d.window ?: return
        w.setType(type)
        w.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        w.setFormat(PixelFormat.TRANSLUCENT)
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // Not focusable: the text field keeps focus, whichever screen it is on.
        w.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        )
        w.attributes = w.attributes.apply {
            title = "ThorKeyboard"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            windowAnimations = 0
        }
        val o = OverlayOwner()
        val v = ComposeView(ctx)
        o.attach(v)
        v.setContent { ThorTheme { KeyboardPanel(ui = ui, visible = visible) } }
        d.setContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        d.setCancelable(false)
        try {
            d.show()
            o.resume()
            dialog = d
            owner = o
        } catch (e: Exception) {
            Log.w(TAG, "show keyboard failed", e)
            o.destroy()
        }
    }

    private fun removeNow() {
        main.removeCallbacks(removeRunnable)
        val d = dialog ?: return
        dialog = null
        try {
            d.dismiss()
        } catch (_: Exception) {
        }
        owner?.destroy()
        owner = null
    }

    private const val PREVIEW_CHARS = 60
    private const val MAX_WORDS = 200
}
