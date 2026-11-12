package com.thorctl.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thorctl.app.RecentsController.Card
import com.thorctl.app.RecentsController.Intro
import com.thorctl.app.RecentsController.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Strong ease in-out, no overshoot: every zoom between a full-screen app and its card, and the
 * keyboard sliding in and out.
 */
internal val StrongInOut = CubicBezierEasing(0.76f, 0f, 0.24f, 1f)
private val Lift = CubicBezierEasing(0.4f, 0f, 1f, 1f)

/** A card is the full screen at this scale. */
private const val CARD_SCALE = 0.7f

/** Backdrop and text colors; the backdrop is the bottom screen's (Cocoon's) background. */
@Immutable
private class RecentsColors(
    val backdropTop: Color,
    val backdropBottom: Color,
    /** Wash over the cards beside the centre one. */
    val backdropMid: Color,
    val ink: Color,
    val inkSecondary: Color,
    val chip: Color,
    /** Behind a card that has no picture yet. */
    val cardFill: Color,
)

// Both backdrops are linear vertical gradients, sampled from Cocoon on the panel.
private val LightColors = RecentsColors(
    backdropTop = Color(0xFFEFEFF3),
    backdropBottom = Color(0xFFCFCFDA),
    backdropMid = Color(0xFFDFDFE6),
    ink = Color(0xFF1B1B1F),
    inkSecondary = Color(0xFF5E5E6A),
    chip = Color(0x14000000),
    cardFill = Color.White,
)
private val DarkColors = RecentsColors(
    backdropTop = Color(0xFF2A2A2A),
    backdropBottom = Color(0xFF1E1E1E),
    backdropMid = Color(0xFF242424),
    ink = Color(0xFFECECEC),
    inkSecondary = Color(0xFFA0A0A0),
    chip = Color(0x29FFFFFF),
    cardFill = Color(0xFF303030),
)

/** Follows the system dark theme setting, like Cocoon. */
@Composable
private fun recentsColors(): RecentsColors = if (isSystemInDarkTheme()) DarkColors else LightColors

/** What the stage asks the controller to do. */
interface RecentsActions {
    /** Zooming into an app card (start of the animation). */
    fun openApp(card: Card)

    /** Zooming into the home card; [alreadyThere] when the home screen is what's underneath. */
    fun openHome(alreadyThere: Boolean)

    /** Zooming back into the app recents was opened from (it is still underneath). */
    fun back()
    fun remove(card: Card)
    fun clearAll(ids: List<Int>)

    /** Home press: the overlay now covers the app, switch to the home screen underneath. */
    fun homeGo()

    /** Zoom finished; remove the overlay once the switch underneath has settled. */
    fun finish(settleMs: Long)
    fun tick()
}

/**
 * Recent apps as a row of equal cards seen through a camera: at zoom 1 the card under the camera
 * fills the screen exactly like the live app, at zoom 0 every card is [CARD_SCALE] of the screen.
 * Oldest apps on the left, the app you came from next, the home screen always rightmost; moving
 * onto the home card opens it. Controller first: ◀ ▶ (held: repeats) move, A opens, X closes,
 * Y closes all, B goes back. Touch: flick one card at a time, tap to open, swipe up to close.
 */
@Composable
fun RecentsStage(
    session: Session,
    cards: StateFlow<List<Card>>,
    keys: SharedFlow<String>,
    actions: RecentsActions,
) {
    val all by cards.collectAsState()
    var removed by remember { mutableStateOf(emptySet<Int>()) }
    val items = all.filter { it.key !in removed }
    val scope = rememberCoroutineScope()

    /** The row as of now (snapshot state, so writes made this frame are already visible). */
    fun current(): List<Card> = cards.value.filter { it.key !in removed }

    val posA = remember { Animatable(session.start.toFloat()) }
    val zoomA = remember { Animatable(1f) }
    val shift = remember { Animatable(0f) }
    var shiftRange by remember { mutableStateOf(IntRange.EMPTY) }
    val lift = remember { Animatable(0f) }
    var liftKeys by remember { mutableStateOf(emptySet<Int>()) }
    var target by remember { mutableIntStateOf(session.start) }
    var locked by remember { mutableStateOf(true) }
    val chrome = session.intro != Intro.HOME_PRESS

    // Home press is one motion from one progress value: the app shrinks into its card while the
    // row slides one card right, and the home card coming in from the right grows to full screen.
    val press = remember { Animatable(0f) }
    val pressing = session.intro == Intro.HOME_PRESS
    fun pressQ() = StrongInOut.transform(press.value)
    fun posV(): Float = if (pressing) pressQ() * (cards.value.size - 1) else posA.value
    fun zoomV(): Float = if (pressing) pressQ().let { q -> 1f - 4f * q * (1f - q) } else zoomA.value

    // ---- Moves ------------------------------------------------------------------------------

    fun homeIsUnderneath() = session.intro == Intro.FROM_HOME

    fun zoomInto(index: Int, settleMs: Long, onStart: () -> Unit) {
        locked = true
        target = index
        onStart()
        // Gliding to the card and zooming into it are one camera move (same curve and timing).
        val spec = tween<Float>(if (abs(posA.value - index) > 0.05f) 480 else 370, easing = StrongInOut)
        scope.launch { posA.animateTo(index.toFloat(), spec) }
        scope.launch {
            zoomA.animateTo(1f, spec)
            actions.finish(settleMs)
        }
    }

    fun openHome() {
        val there = homeIsUnderneath()
        zoomInto(current().lastIndex, if (there) 0 else 380) { actions.openHome(there) }
    }

    fun open(index: Int) {
        val c = current().getOrNull(index) ?: return
        if (c.isHome) return openHome()
        val origin = session.intro == Intro.FROM_APP && c.key == session.originKey
        zoomInto(index, if (origin) 0 else 380) { if (origin) actions.back() else actions.openApp(c) }
    }

    fun back() {
        val i = current().indexOfFirst { it.key == session.originKey }
        if (session.intro == Intro.FROM_APP && i >= 0) open(i) else openHome()
    }

    fun glideTo(t: Int) {
        if (current()[t].isHome) openHome() else scope.launch { posA.animateTo(t.toFloat(), spring(dampingRatio = 1f, stiffness = 380f)) }
    }

    fun move(d: Int) {
        val t = (target + d).coerceIn(0, current().lastIndex)
        if (t == target) return
        target = t
        actions.tick()
        glideTo(t)
    }

    /** Takes cards out of the row and closes the gap smoothly (or goes home if none are left). */
    fun drop(keysToDrop: Set<Int>) {
        val before = current()
        val i = target
        val after = before.filter { it.key !in keysToDrop }
        val rightIsHome = before.getOrNull(i + 1)?.isHome == true
        scope.launch {
            removed = removed + keysToDrop
            if (after.none { !it.isHome }) {
                target = after.lastIndex
                posA.snapTo(after.lastIndex.toFloat())
                openHome()
                return@launch
            }
            if (rightIsHome && i - 1 >= 0) {
                // The app next to home closed: its older neighbour slides right into its place.
                target = i - 1
                posA.snapTo((i - 1).toFloat())
                shiftRange = 0..(i - 1)
                shift.snapTo(-1f)
            } else {
                // The newer neighbour (and everything right of it) slides left into the gap.
                target = i.coerceAtMost(after.lastIndex)
                shiftRange = i..after.lastIndex
                shift.snapTo(1f)
            }
            locked = false
            shift.animateTo(0f, spring(dampingRatio = 1f, stiffness = 420f))
        }
    }

    fun closeCurrent() {
        val c = current().getOrNull(target) ?: return
        if (c.isHome) return
        locked = true
        scope.launch {
            liftKeys = setOf(c.key)
            lift.animateTo(1f, tween(220, easing = Lift))
            actions.remove(c)
            liftKeys = emptySet()
            lift.snapTo(0f)
            drop(setOf(c.key))
        }
    }

    fun closeAll() {
        val apps = current().filter { !it.isHome }
        if (apps.isEmpty()) return openHome()
        locked = true
        scope.launch {
            liftKeys = apps.map { it.key }.toSet()
            lift.snapTo(0f)
            lift.animateTo(1f, tween(260, easing = Lift))
            actions.clearAll(apps.map { it.key })
            removed = removed + liftKeys
            liftKeys = emptySet()
            lift.snapTo(0f)
            target = 0
            posA.snapTo(0f)
            openHome()
        }
    }

    // ---- Intro ------------------------------------------------------------------------------

    LaunchedEffect(Unit) {
        when (session.intro) {
            Intro.FROM_APP -> {
                zoomA.animateTo(0f, tween(370, easing = StrongInOut))
                locked = false
            }
            Intro.FROM_HOME -> {
                // One motion: home pulls back into its card while the row glides over to the most
                // recent app (same curve and timing for both, so they move as one camera move).
                val spec = tween<Float>(510, easing = StrongInOut)
                if (current().size > 1) {
                    target = current().lastIndex - 1
                    launch { posA.animateTo(target.toFloat(), spec) }
                }
                zoomA.animateTo(0f, spec)
                locked = false
            }
            Intro.HOME_PRESS -> {
                actions.homeGo()
                press.animateTo(1f, tween(550, easing = LinearEasing))
                actions.finish(380)
            }
        }
    }

    LaunchedEffect(Unit) {
        keys.collect { k ->
            // Presses during an animation wait for it (a quick ◀ after opening still counts).
            if (locked) snapshotFlow { locked }.first { !it }
            when (k) {
                "left" -> move(-1)
                "right" -> move(1)
                "a" -> open(target)
                "b" -> back()
                "x" -> closeCurrent()
                "y" -> closeAll()
                "home" -> openHome()
            }
        }
    }

    // ---- Drawing ----------------------------------------------------------------------------

    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val gap = with(density) { 36.dp.toPx() }
        val step = w * CARD_SCALE + gap
        val radius = 5f // px on screen: cards are only just rounded
        val elevation = with(density) { 10.dp.toPx() }
        val labelLift = with(density) { 46.dp.toPx() }
        val band = h * (1f - CARD_SCALE) / 2f

        fun camera() = 1f + (1f / CARD_SCALE - 1f) * zoomV()
        fun rel(i: Int) = i - posV() + (if (i in shiftRange) shift.value else 0f)

        /** Index of the card under a point (at rest, zoom 0), or -1. */
        fun cardAt(o: Offset): Int {
            val cw = w * CARD_SCALE
            val ch = h * CARD_SCALE
            if (o.y < (h - ch) / 2 || o.y > (h + ch) / 2) return -1
            for (i in current().indices) {
                val cx = w / 2 + rel(i) * step
                if (o.x >= cx - cw / 2 && o.x <= cx + cw / 2) return i
            }
            return -1
        }

        // Backdrop: solid, the same gradient as the bottom screen (light or dark). At zoom 1 the
        // card covers it completely, so it simply appears around the card as the card shrinks.
        val rc = recentsColors()
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(rc.backdropTop, rc.backdropBottom))))

        items.forEachIndexed { i, c ->
            key(c.key) {
                CardLayer(
                    card = c,
                    layer = {
                        val cam = camera()
                        val s = CARD_SCALE * cam
                        val r = rel(i)
                        scaleX = s
                        scaleY = s
                        translationX = r * step * cam
                        val lifted = c.key in liftKeys
                        translationY = if (lifted) -lift.value * h * 0.9f else 0f
                        alpha = if (abs(r) > 2.6f) 0f else if (lifted) 1f - lift.value else 1f
                        shape = RoundedCornerShape(((radius * (1f - zoomV())) / s).toDp())
                        clip = true
                        shadowElevation = elevation * (1f - zoomV()) / s
                    },
                    dim = { abs(rel(i)).coerceAtMost(1f) * 0.5f * (1f - zoomV()) },
                )
            }
        }

        if (chrome) {
            // Labels above the cards, drawn unscaled so the text stays sharp.
            items.forEachIndexed { i, c ->
                key("label${c.key}") {
                    CardLabel(
                        card = c,
                        width = with(density) { (w * CARD_SCALE).toDp() },
                        modifier = Modifier.graphicsLayer {
                            val r = rel(i)
                            val cam = camera()
                            translationX = w / 2 + r * step * cam - w * CARD_SCALE * cam / 2
                            translationY = h / 2 - h * CARD_SCALE * cam / 2 - labelLift
                            val lifted = c.key in liftKeys
                            alpha = if (abs(r) > 2.6f) 0f else
                                (1f - zoomV()) * (1f - abs(r).coerceAtMost(1f) * 0.5f) * (if (lifted) 1f - lift.value else 1f)
                        },
                    )
                }
            }
            // Controller hints, centred in the band under the cards: even space above and below.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(with(density) { band.toDp() })
                    .graphicsLayer { alpha = 1f - zoomV() },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (items.size > 1) {
                        Hint("◀ ▶", "Switch")
                        Hint("A", "Open")
                        Hint("X", "Close")
                        Hint("Y", "Close all")
                    } else {
                        Text("No recent apps", style = MaterialTheme.typography.bodyMedium, color = rc.inkSecondary)
                        Spacer(Modifier.width(24.dp))
                    }
                    Hint("B", "Back")
                }
            }
        }

        // Touch: flick one card at a time, tap to open, swipe the centre card up to close it.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (locked) return@awaitEachGesture
                        val startPos = posA.value
                        val hit = cardAt(down.position)
                        val vt = VelocityTracker()
                        vt.addPosition(down.uptimeMillis, down.position)
                        var total = Offset.Zero
                        var axis = 0
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            total += ch.positionChange()
                            vt.addPosition(ch.uptimeMillis, ch.position)
                            if (axis == 0 && total.getDistance() > viewConfiguration.touchSlop) {
                                axis = if (abs(total.x) >= abs(total.y)) 1 else 2
                            }
                            if (axis == 1) {
                                ch.consume()
                                val p = (startPos - total.x / step).coerceIn(-0.3f, current().lastIndex - 0.7f)
                                scope.launch { posA.snapTo(p) }
                            } else if (axis == 2 && hit == target && current().getOrNull(hit)?.isHome == false) {
                                ch.consume()
                                liftKeys = setOf(current()[hit].key)
                                scope.launch { lift.snapTo((-total.y / (h * 0.9f)).coerceIn(0f, 1f)) }
                            }
                        }
                        val v = vt.calculateVelocity()
                        when (axis) {
                            0 -> if (hit >= 0) open(hit) else back()
                            1 -> {
                                val base = startPos.roundToInt()
                                val t = when {
                                    v.x < -500f -> base + 1
                                    v.x > 500f -> base - 1
                                    else -> posA.value.roundToInt()
                                }.coerceIn(0, current().lastIndex)
                                if (t != target) actions.tick()
                                target = t
                                glideTo(t)
                            }
                            2 -> if (liftKeys.isNotEmpty()) {
                                if (lift.value > 0.22f || v.y < -1400f) {
                                    closeCurrent()
                                } else {
                                    scope.launch {
                                        lift.animateTo(0f, spring(dampingRatio = 1f, stiffness = 500f))
                                        liftKeys = emptySet()
                                    }
                                }
                            }
                        }
                    }
                },
        )
    }
}

@Composable
private fun CardLayer(card: Card, layer: GraphicsLayerScope.() -> Unit, dim: () -> Float) {
    val rc = recentsColors()
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer(layer)
            .background(rc.cardFill),
    ) {
        val img = card.image
        if (img != null) {
            Image(
                img,
                card.label,
                Modifier.fillMaxSize(),
                contentScale = if (card.onBottom) ContentScale.Fit else ContentScale.Crop,
            )
        } else if (card.icon != null) {
            Image(card.icon, card.label, Modifier.align(Alignment.Center).size(96.dp))
        }
        // Cards beside the centre one fade towards the backdrop.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = dim() }
                .background(rc.backdropMid),
        )
    }
}

@Composable
private fun CardLabel(card: Card, width: Dp, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val qs = qsColors()
    val rc = recentsColors()
    Row(modifier.width(width).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (card.isHome) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(qs.iconActive), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Home, null, tint = qs.onIconActive, modifier = Modifier.size(18.dp))
            }
        } else if (card.icon != null) {
            Image(card.icon, null, Modifier.size(30.dp))
        } else {
            Box(Modifier.size(30.dp).clip(CircleShape).background(rc.chip))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (card.isHome) "Home" else card.label,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
            color = rc.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (card.onBottom) {
            Spacer(Modifier.width(8.dp))
            Text(
                "Bottom screen",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSecondaryContainer,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(cs.secondaryContainer).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Hint(button: String, action: String) {
    val rc = recentsColors()
    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .height(28.dp)
                .width(if (button.length > 1) 48.dp else 28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(rc.chip),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                button,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = rc.ink,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(action, style = MaterialTheme.typography.bodyMedium, color = rc.ink)
    }
}
