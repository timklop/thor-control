package com.thorctl.ui

import android.os.VibrationEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.HighlightAlt
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorctl.app.KeyboardController
import com.thorctl.app.KeyboardController.Enter
import com.thorctl.app.KeyboardController.Layer
import com.thorctl.app.OverlayController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// Cocoon's keyboard look: white rounded keys with a soft shadow on the bottom screen's gradient.
private val KbTop = Color(0xFFEFEFF3)
private val KbBottom = Color(0xFFCFCFDA)
private val KeyFill = Color(0xFFFFFFFF)
private val KeyPressed = Color(0xFFE2E2EA)
/** Function keys (shift, backspace, layer keys, enter): iOS's darker grey. */
private val FnFill = Color(0xFFADB3BC)
private val KeyInk = Color(0xFF1B1B1F)
private val KeyInkSoft = Color(0xFF6B6B78)
private val Accent = Color(0xFF6AA6F8)
private val AccentDeep = Color(0xFF4F8EF0)
private val PadFill = Color(0x8CFFFFFF)
private val KeyShape = RoundedCornerShape(12.dp)
private val KeyShadow = Color(0x40000000)

/**
 * The keyboard on the bottom screen: the lower half is a staggered QWERTY keyboard, the upper half
 * a touchpad that moves the cursor one character per step (with a tick for every character), with
 * Select / Copy / Paste above it and a preview of the text around the cursor on it.
 */
@Composable
fun KeyboardPanel(ui: StateFlow<KeyboardController.Ui>, visible: StateFlow<Boolean>) {
    val state by ui.collectAsState()
    val shown by visible.collectAsState()
    // Starts hidden, so even a freshly created window slides in. The whole panel slides up from
    // below the screen and back down, on the same strong in-out curve as the recents screen.
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = shown
    AnimatedVisibility(
        visibleState = transition,
        enter = slideInVertically(tween(330, easing = StrongInOut)) { it },
        exit = slideOutVertically(tween(290, easing = StrongInOut)) { it },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(KbTop, KbBottom)))) {
            val keysHeight = maxHeight * 0.5f
            val popup = remember { KeyPopup() }
            popup.panelW = constraints.maxWidth.toFloat()
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().weight(1f).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)) {
                    ToolRow(state)
                    Spacer(Modifier.height(10.dp))
                    Touchpad(state, Modifier.fillMaxWidth().weight(1f))
                }
                // Keys draw 3 dp inside their cells; the cells themselves touch, so there is no
                // dead space between keys (and only 2 dp at the screen edges).
                Keys(state, popup, Modifier.fillMaxWidth().height(keysHeight).padding(start = 2.dp, end = 2.dp, bottom = 3.dp))
            }
            AccentPopup(popup)
        }
    }
}

// ---- Select / Copy / Paste ----------------------------------------------------------------------

@Composable
private fun ToolRow(state: KeyboardController.Ui) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Pill(Icons.Rounded.HighlightAlt, "Select", active = state.selecting) {
            KeyboardController.toggleSelect()
        }
        Spacer(Modifier.width(10.dp))
        Pill(Icons.Rounded.ContentCopy, "Copy") { KeyboardController.copy() }
        Spacer(Modifier.width(10.dp))
        Pill(Icons.Rounded.ContentPaste, "Paste") { KeyboardController.paste() }
        Spacer(Modifier.weight(1f))
        HideButton()
    }
}

@Composable
private fun HideButton() {
    Box(
        Modifier
            .size(44.dp)
            .raised(22.dp, pressed = false)
            .clip(CircleShape)
            .background(KeyFill)
            .pointerInput(Unit) { detectTapGestures(onTap = { KeyboardController.hideKeyboard() }) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.KeyboardHide, "Hide keyboard", tint = KeyInk, modifier = Modifier.size(22.dp))
    }
}

// ---- Upright: a regular keyboard on the top screen -----------------------------------------------

/** Height of the keyboard on the top screen when the Thor is held upright. */
val INLINE_KEYBOARD_HEIGHT: Dp = 268.dp

/**
 * Held upright (the top screen in portrait), the keyboard is an ordinary on-screen keyboard at the
 * bottom of the top screen, under the app: the same keys, with Paste and hide above them (no
 * touchpad; the text field is right there to tap).
 */
@Composable
fun InlineKeyboard(ui: StateFlow<KeyboardController.Ui>) {
    val state by ui.collectAsState()
    val popup = remember { KeyPopup() }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(INLINE_KEYBOARD_HEIGHT)
            .background(Brush.verticalGradient(listOf(KbTop, KbBottom))),
    ) {
        popup.panelW = constraints.maxWidth.toFloat()
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(Icons.Rounded.ContentPaste, "Paste") { KeyboardController.paste() }
                Spacer(Modifier.weight(1f))
                HideButton()
            }
            Keys(state, popup, Modifier.fillMaxWidth().weight(1f).padding(start = 2.dp, end = 2.dp, bottom = 3.dp))
        }
        AccentPopup(popup)
    }
}

@Composable
private fun Pill(icon: ImageVector, label: String, active: Boolean = false, onTap: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val fill = when {
        active -> Brush.verticalGradient(listOf(Accent, AccentDeep))
        pressed -> Brush.verticalGradient(listOf(KeyPressed, KeyPressed))
        else -> Brush.verticalGradient(listOf(KeyFill, KeyFill))
    }
    val ink = if (active) Color.White else KeyInk
    Row(
        Modifier
            .height(44.dp)
            .raised(22.dp, pressed)
            .clip(RoundedCornerShape(22.dp))
            .background(fill)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        OverlayController.haptic(VibrationEffect.EFFECT_TICK)
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onTap() },
                )
            }
            .padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
    }
}

// ---- Touchpad -----------------------------------------------------------------------------------

@Composable
private fun Touchpad(state: KeyboardController.Ui, modifier: Modifier) {
    val stepPx = with(LocalDensity.current) { STEP.toPx() }
    var touching by remember { mutableStateOf(false) }
    val glow by animateFloatAsState(if (touching) 1f else 0f, tween(160), label = "pad")
    Box(
        modifier
            .clip(KeyShape)
            .background(PadFill)
            .background(Accent.copy(alpha = 0.10f * glow))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    touching = true
                    KeyboardController.beginSwipe()
                    var acc = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: break
                        if (!ch.pressed) break
                        acc += ch.positionChange().x
                        ch.consume()
                        while (acc >= stepPx) {
                            acc -= stepPx
                            KeyboardController.move(1)
                        }
                        while (acc <= -stepPx) {
                            acc += stepPx
                            KeyboardController.move(-1)
                        }
                    }
                    KeyboardController.endSwipe()
                    touching = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Preview(state, Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Swipe, null, tint = KeyInkSoft, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (state.selecting) "Swipe to select" else "Swipe to move the cursor",
                color = KeyInkSoft,
                fontSize = 13.sp,
            )
        }
        Flash(state, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
    }
}

/** One line of text around the cursor, the caret (or the selection) kept in the middle. */
@Composable
private fun Preview(state: KeyboardController.Ui, modifier: Modifier) {
    val style = androidx.compose.ui.text.TextStyle(color = KeyInk, fontSize = 22.sp)
    Row(modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds(), contentAlignment = Alignment.CenterEnd) {
            Text(state.before, style = style, softWrap = false, maxLines = 1, modifier = Modifier.wrapContentWidth(Alignment.End, unbounded = true))
        }
        if (state.selected.isEmpty()) {
            Box(Modifier.width(2.dp).height(30.dp).background(AccentDeep))
        } else {
            Text(
                state.selected,
                style = style,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Accent.copy(alpha = 0.32f)).padding(horizontal = 2.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds(), contentAlignment = Alignment.CenterStart) {
            Text(state.after, style = style.copy(color = KeyInk.copy(alpha = 0.55f)), softWrap = false, maxLines = 1, modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true))
        }
    }
}

@Composable
private fun Flash(state: KeyboardController.Ui, modifier: Modifier) {
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.flashId) {
        if (state.flash == null) return@LaunchedEffect
        text = state.flash
        delay(1300)
        text = null
    }
    val a by animateFloatAsState(if (text != null) 1f else 0f, tween(150), label = "flash")
    if (a > 0f || text != null) {
        Text(
            text ?: "",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = modifier
                .graphicsLayer { alpha = a }
                .clip(RoundedCornerShape(14.dp))
                .background(KeyInk.copy(alpha = 0.82f))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

// ---- Keys ---------------------------------------------------------------------------------------

private sealed interface Key {
    val w: Float
}

/**
 * A character key. [alt] is typed by swiping down on the key (it is shown small above the
 * character); [lead] / [trail] widen the key's touch area (not the key) into the half-key gaps of
 * the staggered row.
 */
private data class Ch(val c: String, val alt: String?, val lead: Float = 0f, val trail: Float = 0f) : Key {
    override val w: Float get() = 1f + lead + trail
}

private data class Fn(val kind: FnKind, override val w: Float) : Key
private enum class FnKind { SHIFT, BACK, SPACE, ENTER, TO_LETTERS, TO_SYMBOLS, TO_MORE }

// Every layer has the same key positions; a key's swipe-down character is the next layer's key in
// the same spot (letters -> ?123 -> =\<), like the iPad keyboard.
private val LETTER_ROWS = listOf("q w e r t y u i o p", "a s d f g h j k l", "z x c v b n m", ", .")
private val SYMBOL_ROWS = listOf("1 2 3 4 5 6 7 8 9 0", "@ # \$ & * ( ) ' \"", "% - + = / ; :", "! ?")
private val MORE_ROWS = listOf("[ ] { } ^ ~ < > | \\", "_ € £ ¥ ¢ • ° … ¶", "© ® ™ ✓ × ÷ §", "¡ ¿")

private fun rows(layer: Layer): List<List<Key>> {
    val (main, alt) = when (layer) {
        Layer.LETTERS -> LETTER_ROWS to SYMBOL_ROWS
        Layer.SYMBOLS -> SYMBOL_ROWS to MORE_ROWS
        Layer.MORE -> MORE_ROWS to null
    }
    fun row(i: Int): List<Ch> {
        val alts = alt?.get(i)?.split(' ')
        return main[i].split(' ').mapIndexed { j, c -> Ch(c, alts?.getOrNull(j)) }
    }
    val r2 = row(1).let { r -> r.mapIndexed { j, k -> k.copy(lead = if (j == 0) 0.5f else 0f, trail = if (j == r.lastIndex) 0.5f else 0f) } }
    val r4 = row(3)
    val (left3, left4) = when (layer) {
        Layer.LETTERS -> FnKind.SHIFT to FnKind.TO_SYMBOLS
        Layer.SYMBOLS -> FnKind.TO_MORE to FnKind.TO_LETTERS
        Layer.MORE -> FnKind.TO_SYMBOLS to FnKind.TO_LETTERS
    }
    return listOf(
        row(0),
        r2,
        listOf(Fn(left3, 1.5f)) + row(2) + Fn(FnKind.BACK, 1.5f),
        listOf(Fn(left4, 1.5f), r4[0], Fn(FnKind.SPACE, 5f), r4[1], Fn(FnKind.ENTER, 1.5f)),
    )
}

/** Hold a key for these (slide along the strip, release to type). */
private val ACCENTS: Map<String, List<String>> = mapOf(
    "a" to "à á â ä æ ã å ā", "c" to "ç ć č", "d" to "ď ð", "e" to "è é ê ë ē ė ę ě", "g" to "ğ",
    "i" to "î ï í ī į ì", "l" to "ł", "n" to "ñ ń ň", "o" to "ô ö ò ó œ ø ō õ", "r" to "ř",
    "s" to "ß ś š", "t" to "ť þ", "u" to "û ü ù ú ū ů", "y" to "ÿ ý", "z" to "ž ź ż",
    "-" to "– — ·", "\$" to "€ £ ¥ ¢", "!" to "¡", "?" to "¿", "'" to "‘ ’ ‚", "\"" to "“ ” „ « »",
    "." to "…", "0" to "°", "%" to "‰", "*" to "★ †",
).mapValues { it.value.split(' ') }

private const val LONG_PRESS_MS = 380L

/** The accent strip shown above a held key; positions are in window (root) pixels. */
@Stable
private class KeyPopup {
    var options by mutableStateOf<List<String>>(emptyList())
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var cellW by mutableFloatStateOf(0f)
    var cellH by mutableFloatStateOf(0f)
    var selected by mutableIntStateOf(0)
    var panelW = 0f

    fun indexAt(rootX: Float): Int = ((rootX - x) / cellW).toInt().coerceIn(0, (options.size - 1).coerceAtLeast(0))

    fun show(opts: List<String>, face: Rect, fingerX: Float, gap: Float) {
        cellW = face.width
        cellH = face.height
        val total = cellW * opts.size
        // Grow to the right of the key, or to the left when that would leave the screen.
        x = if (face.left + total <= panelW - gap) face.left else face.right - total
        x = x.coerceIn(gap, (panelW - total - gap).coerceAtLeast(gap))
        y = (face.top - cellH - gap).coerceAtLeast(0f)
        options = opts
        selected = indexAt(fingerX)
    }
}

@Composable
private fun AccentPopup(p: KeyPopup) {
    if (p.options.isEmpty()) return
    val d = LocalDensity.current
    Row(
        Modifier
            .offset { IntOffset(p.x.roundToInt(), p.y.roundToInt()) }
            .size(with(d) { (p.cellW * p.options.size).toDp() }, with(d) { p.cellH.toDp() })
            .shadow(10.dp, KeyShape, ambientColor = KeyShadow, spotColor = KeyShadow)
            .clip(KeyShape)
            .background(KeyFill)
            .padding(4.dp),
    ) {
        p.options.forEachIndexed { i, o ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (i == p.selected) Accent else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Text(o, color = if (i == p.selected) Color.White else KeyInk, fontSize = 24.sp)
            }
        }
    }
}

@Composable
private fun Keys(state: KeyboardController.Ui, popup: KeyPopup, modifier: Modifier) {
    val layout = rows(state.layer)
    Column(modifier) {
        layout.forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                row.forEachIndexed { i, k ->
                    // A fresh key per layer and position: no state or handlers carry over.
                    key(state.layer, r, i) {
                        when (k) {
                            is Ch -> CharKey(k, state, popup)
                            is Fn -> FnKey(k, state)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A character key, iPad style: tap types it, swiping down types the small character shown above
 * it (that character grows into place while the letter slides down and fades), holding opens the
 * accent strip. The whole cell is the touch area; the key is drawn inset.
 */
@Composable
private fun RowScope.CharKey(k: Ch, state: KeyboardController.Ui, popup: KeyPopup) {
    val shifted = state.layer == Layer.LETTERS && (state.shift || state.caps)
    val label = if (shifted) k.c.uppercase() else k.c
    val accents = ACCENTS[k.c].orEmpty().map { if (shifted && it != "ß") it.uppercase() else it }
    val accentsNow by rememberUpdatedState(accents)
    var pressed by remember { mutableStateOf(false) }
    val swipe = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var cellRoot by remember { mutableStateOf(Offset.Zero) }
    var face by remember { mutableStateOf(Rect.Zero) }
    val gap = with(LocalDensity.current) { 8.dp.toPx() }
    Box(
        Modifier
            .weight(k.w)
            .fillMaxHeight()
            .onGloballyPositioned { cellRoot = it.positionInRoot() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val acc = accentsNow
                    pressed = true
                    OverlayController.haptic(VibrationEffect.EFFECT_TICK)
                    var mode = PRESS
                    var released = false
                    var past = false
                    val slop = viewConfiguration.touchSlop
                    val swipeDist = (if (face.height > 0f) face.height else size.height.toFloat()) * 0.45f
                    val hold = scope.launch {
                        delay(LONG_PRESS_MS)
                        if (mode == PRESS && acc.isNotEmpty()) {
                            mode = ACCENTS_OPEN
                            popup.show(acc, face, cellRoot.x + down.position.x, gap)
                            OverlayController.haptic(VibrationEffect.EFFECT_TICK)
                        }
                    }
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            released = true
                            break
                        }
                        val d = ch.position - down.position
                        when (mode) {
                            PRESS -> if (k.alt != null && d.y > slop && d.y > abs(d.x)) {
                                mode = SWIPE
                                hold.cancel()
                            } else if (d.getDistance() > slop * 3) {
                                hold.cancel()
                            }
                            SWIPE -> {
                                val p = (d.y / swipeDist).coerceIn(0f, 1f)
                                scope.launch { swipe.snapTo(p) }
                                if ((p >= 0.5f) != past) {
                                    past = p >= 0.5f
                                    OverlayController.haptic(VibrationEffect.EFFECT_TICK)
                                }
                            }
                            ACCENTS_OPEN -> {
                                val i = popup.indexAt(cellRoot.x + ch.position.x)
                                if (i != popup.selected) {
                                    popup.selected = i
                                    OverlayController.haptic(VibrationEffect.EFFECT_TICK)
                                }
                            }
                        }
                        ch.consume()
                    }
                    hold.cancel()
                    if (released) {
                        when (mode) {
                            PRESS -> KeyboardController.type(k.c)
                            SWIPE -> if (past && k.alt != null) KeyboardController.typeExact(k.alt) else KeyboardController.type(k.c)
                            ACCENTS_OPEN -> popup.options.getOrNull(popup.selected)?.let { KeyboardController.typeExact(it) }
                        }
                    }
                    popup.options = emptyList()
                    pressed = false
                    scope.launch { swipe.animateTo(0f, spring(dampingRatio = 1f, stiffness = 900f)) }
                }
            },
    ) {
        Row(Modifier.fillMaxSize()) {
            if (k.lead > 0f) Spacer(Modifier.weight(k.lead))
            KeyFace(
                pressed = pressed,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onFace = { face = it },
            ) { CharLabels(label, k.alt, swipe) }
            if (k.trail > 0f) Spacer(Modifier.weight(k.trail))
        }
    }
}

private const val PRESS = 0
private const val SWIPE = 1
private const val ACCENTS_OPEN = 2

/** The character, with the swipe-down character small above it; [swipe] 0..1 swaps them. */
@Composable
private fun CharLabels(label: String, alt: String?, swipe: Animatable<Float, *>) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val h = constraints.maxHeight.toFloat()
        if (alt != null) {
            Text(
                alt,
                color = KeyInk,
                fontSize = 24.sp,
                modifier = Modifier.graphicsLayer {
                    val p = swipe.value as Float
                    val s = 0.5f + 0.5f * p
                    scaleX = s
                    scaleY = s
                    translationY = -h * 0.27f * (1f - p)
                    alpha = 0.42f + 0.58f * p
                },
            )
        }
        Text(
            label,
            color = KeyInk,
            fontSize = 24.sp,
            modifier = Modifier.graphicsLayer {
                val p = swipe.value as Float
                translationY = (if (alt != null) h * 0.09f else 0f) + h * 0.55f * p
                alpha = 1f - 0.8f * p
                val s = 1f - 0.15f * p
                scaleX = s
                scaleY = s
            },
        )
    }
}

@Composable
private fun RowScope.FnKey(k: Fn, state: KeyboardController.Ui) {
    val m = Modifier.weight(k.w).fillMaxHeight()
    when (k.kind) {
        FnKind.SHIFT -> FnCell(m, active = state.shift || state.caps, onTap = { KeyboardController.shift() }) {
            ShiftGlyph(filled = state.shift || state.caps, underline = state.caps, tint = KeyInk)
        }
        FnKind.BACK -> BackspaceKey(m)
        FnKind.SPACE -> FnCell(m, fn = false, onTap = { KeyboardController.space() }) {}
        FnKind.ENTER -> FnCell(m, onTap = { KeyboardController.enter() }) {
            Icon(enterIcon(state.enter), "Enter", tint = KeyInk, modifier = Modifier.size(26.dp))
        }
        FnKind.TO_LETTERS -> LayerKey(m, "ABC", Layer.LETTERS)
        FnKind.TO_SYMBOLS -> LayerKey(m, "?123", Layer.SYMBOLS)
        FnKind.TO_MORE -> LayerKey(m, "=\\<", Layer.MORE)
    }
}

/**
 * Backspace, Gboard style: tap deletes a character, hold repeats, swipe left selects whole words
 * back from the cursor (swipe back right to give them back) and letting go deletes them.
 */
@Composable
private fun BackspaceKey(m: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val wordStep = with(LocalDensity.current) { WORD_STEP.toPx() }
    Box(
        m.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                pressed = true
                OverlayController.haptic(VibrationEffect.EFFECT_TICK)
                val slop = viewConfiguration.touchSlop
                var mode = PRESS
                var released = false
                val repeat = scope.launch {
                    delay(420)
                    if (mode == PRESS) {
                        mode = REPEATING
                        while (true) {
                            KeyboardController.backspace()
                            delay(55)
                        }
                    }
                }
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) {
                        released = true
                        break
                    }
                    val dx = ch.position.x - down.position.x
                    if (mode == PRESS && dx < -slop) {
                        mode = WORDS
                        repeat.cancel()
                        KeyboardController.beginWordDelete()
                    }
                    if (mode == WORDS) {
                        val back = -dx - slop
                        KeyboardController.selectWords(if (back <= 0f) 0 else (back / wordStep).toInt() + 1)
                    }
                    ch.consume()
                }
                repeat.cancel()
                when (mode) {
                    PRESS -> if (released) KeyboardController.backspace()
                    WORDS -> KeyboardController.endWordDelete(commit = released)
                }
                pressed = false
            }
        },
    ) {
        KeyFace(pressed, Modifier.fillMaxSize(), fn = true, onFace = {}) {
            Icon(Icons.Rounded.Backspace, "Backspace", tint = KeyInk, modifier = Modifier.size(24.dp))
        }
    }
}

private const val REPEATING = 3
private const val WORDS = 4

/** Backspace swipe distance per word. */
private val WORD_STEP: Dp = 28.dp

@Composable
private fun LayerKey(m: Modifier, label: String, to: Layer) {
    FnCell(m, onTap = { KeyboardController.layer(to) }) {
        Text(label, color = KeyInk, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

private fun enterIcon(e: Enter): ImageVector = when (e) {
    Enter.RETURN -> Icons.Rounded.KeyboardReturn
    Enter.GO, Enter.NEXT -> Icons.Rounded.ArrowForward
    Enter.SEARCH -> Icons.Rounded.Search
    Enter.SEND -> Icons.Rounded.Send
    Enter.DONE -> Icons.Rounded.Done
}

/**
 * A function key. The whole cell is the touch area. Acts on release with a tick on touch;
 * [repeat] keys act on touch and keep going while held (backspace).
 */
@Composable
private fun FnCell(m: Modifier, active: Boolean = false, repeat: Boolean = false, fn: Boolean = true, onTap: () -> Unit, content: @Composable () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val action by rememberUpdatedState(onTap)
    val scope = rememberCoroutineScope()
    Box(
        m.pointerInput(repeat) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    OverlayController.haptic(VibrationEffect.EFFECT_TICK)
                    var job: Job? = null
                    if (repeat) {
                        action()
                        job = scope.launch {
                            delay(420)
                            while (true) {
                                action()
                                delay(55)
                            }
                        }
                    }
                    tryAwaitRelease()
                    job?.cancel()
                    pressed = false
                },
                onTap = { if (!repeat) action() },
            )
        },
    ) {
        KeyFace(pressed, Modifier.fillMaxSize(), active = active, fn = fn, onFace = {}) { content() }
    }
}

/** What a key looks like: white, rounded, soft shadow, inset in its cell. */
@Composable
private fun KeyFace(
    pressed: Boolean,
    modifier: Modifier,
    active: Boolean = false,
    fn: Boolean = false,
    onFace: (Rect) -> Unit,
    content: @Composable () -> Unit,
) {
    // Like iOS: character keys white (light grey when pressed); function keys darker grey that
    // flash white when pressed; an active shift turns white with a filled arrow.
    val fill = when {
        active -> KeyFill
        pressed -> if (fn) KeyFill else KeyPressed
        fn -> FnFill
        else -> KeyFill
    }
    Box(
        modifier
            .padding(3.dp)
            .onGloballyPositioned { onFace(it.boundsInRoot()) }
            .raised(12.dp, pressed)
            .clip(KeyShape)
            .background(fill),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Height of the hard edge under each key (the iOS keyboard's "raise"). */
private val LIP: Dp = 2.dp
private val LipColor = Color(0xFF9A9BA6)

/**
 * A key's raise: a hard, unblurred darker edge along its bottom, the face sitting [LIP] above it.
 * Pressed, the face drops onto the edge, so the key looks pushed in. Apply before clip/background.
 */
private fun Modifier.raised(corner: Dp, pressed: Boolean): Modifier = this
    .drawBehind {
        val lip = LIP.toPx()
        drawRoundRect(
            LipColor,
            topLeft = Offset(0f, lip),
            size = Size(size.width, size.height - lip),
            cornerRadius = CornerRadius(corner.toPx()),
        )
    }
    .padding(bottom = LIP)
    .graphicsLayer { translationY = if (pressed) LIP.toPx() else 0f }

/** The ⇧ shift arrow: outlined, filled when shifted, underlined for caps lock. */
@Composable
private fun ShiftGlyph(filled: Boolean, underline: Boolean, tint: Color) {
    Canvas(Modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.5f, h * 0.12f)
            lineTo(w * 0.88f, h * 0.52f)
            lineTo(w * 0.66f, h * 0.52f)
            lineTo(w * 0.66f, h * 0.80f)
            lineTo(w * 0.34f, h * 0.80f)
            lineTo(w * 0.34f, h * 0.52f)
            lineTo(w * 0.12f, h * 0.52f)
            close()
        }
        val stroke = Stroke(width = w * 0.075f, join = StrokeJoin.Round)
        drawPath(p, tint, style = if (filled) Fill else stroke)
        if (filled) drawPath(p, tint, style = stroke)
        if (underline) drawLine(tint, Offset(w * 0.28f, h * 0.94f), Offset(w * 0.72f, h * 0.94f), strokeWidth = w * 0.08f)
    }
}

/** Touchpad distance per character. */
private val STEP: Dp = 11.dp
