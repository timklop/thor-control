package com.thorctl.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorctl.shared.LedState
import com.thorctl.shared.Proto
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Staggered "shade pull" entrance: each row drops in from above with an expressive (slightly
 * bouncy) spring, scaled from the top edge. On exit everything leaves together, quickly.
 */
@Composable
fun Modifier.enter(index: Int, visible: Boolean): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            delay(10L + index * 16L)
            p.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 650f))
        } else {
            p.animateTo(0f, tween(110, easing = FastOutLinearInEasing))
        }
    }
    return this.graphicsLayer {
        val v = p.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * -28.dp.toPx()
        val s = 0.9f + 0.1f * v
        scaleX = s
        scaleY = s
        transformOrigin = TransformOrigin(0.5f, 0f)
    }
}

/**
 * Android 17 Quick Settings brightness slider (M3 Expressive, large): 40dp track with small
 * inner corners, a 4dp-wide handle that sticks out above and below the track and narrows while
 * dragging, icon inset at the end. The label is drawn two-tone where the active track covers it.
 */
@Composable
fun QsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackBrush: Brush? = null,
) {
    val cs = MaterialTheme.colorScheme
    val qs = qsColors()
    val view = LocalView.current
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }
    if (!dragging && local != value) local = value
    var lastStep by remember { mutableIntStateOf(-1) }
    val onChange by rememberUpdatedState(onValueChange)
    val onFinished by rememberUpdatedState(onValueChangeFinished)
    val shown by animateFloatAsState(local, if (dragging) snap() else spring(stiffness = 500f), label = "slider")
    val handleW by animateDpAsState(if (dragging) 2.dp else 4.dp, spring(stiffness = 1400f), label = "handle")

    val active = if (enabled) cs.primary else cs.onSurface.copy(alpha = 0.28f)
    val onActive = if (enabled) cs.onPrimary else cs.surface
    val textMeasurer = rememberTextMeasurer()
    val painter = rememberVectorPainter(icon)
    val labelStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val minX = 26.dp.toPx()
                val maxX = size.width - 2.dp.toPx()
                fun update(x: Float) {
                    val v = ((x - minX) / (maxX - minX)).coerceIn(0f, 1f)
                    local = v
                    val step = (v * 20).roundToInt()
                    if (step != lastStep) {
                        lastStep = step
                        view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                    }
                    onChange(v)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    dragging = true
                    update(down.position.x)
                    drag(down.id) { change ->
                        update(change.position.x)
                        change.consume()
                    }
                    dragging = false
                    onFinished(local)
                }
            },
    ) {
        val w = size.width
        val trackH = 40.dp.toPx()
        val top = (size.height - trackH) / 2f
        val bottom = top + trackH
        val outer = 12.dp.toPx()
        val inner = 2.dp.toPx()
        val hw = handleW.toPx()
        val gap = 6.dp.toPx()
        val minX = 26.dp.toPx()
        val maxX = w - 2.dp.toPx()
        val hx = minX + (maxX - minX) * shown
        val activeEnd = hx - hw / 2f - gap
        val inactiveStart = hx + hw / 2f + gap

        if (inactiveStart < w - 2f) {
            val p = Path().apply {
                addRoundRect(
                    RoundRect(
                        Rect(inactiveStart, top, w, bottom),
                        topLeft = CornerRadius(inner), bottomLeft = CornerRadius(inner),
                        topRight = CornerRadius(outer), bottomRight = CornerRadius(outer),
                    ),
                )
            }
            if (trackBrush != null) drawPath(p, trackBrush, alpha = 0.45f) else drawPath(p, qs.container)
        }
        val ap = Path().apply {
            addRoundRect(
                RoundRect(
                    Rect(0f, top, activeEnd.coerceAtLeast(outer * 1.2f), bottom),
                    topLeft = CornerRadius(outer), bottomLeft = CornerRadius(outer),
                    topRight = CornerRadius(inner), bottomRight = CornerRadius(inner),
                ),
            )
        }
        if (trackBrush != null) drawPath(ap, trackBrush) else drawPath(ap, active)
        drawRoundRect(
            color = if (trackBrush != null) cs.onSurface else active,
            topLeft = Offset(hx - hw / 2f, 0f),
            size = Size(hw, size.height),
            cornerRadius = CornerRadius(hw / 2f),
        )

        // Icon inset at the end of the track.
        val iconSize = 24.dp.toPx()
        val ix = w - 16.dp.toPx() - iconSize
        val iy = (size.height - iconSize) / 2f
        clipRect(right = activeEnd) {
            translate(ix, iy) { with(painter) { draw(Size(iconSize, iconSize), colorFilter = ColorFilter.tint(onActive)) } }
        }
        clipRect(left = activeEnd) {
            translate(ix, iy) { with(painter) { draw(Size(iconSize, iconSize), colorFilter = ColorFilter.tint(cs.onSurfaceVariant)) } }
        }
        // Label at the start.
        val text = textMeasurer.measure(label, labelStyle)
        val tx = 18.dp.toPx()
        val ty = (size.height - text.size.height) / 2f
        clipRect(right = activeEnd) { drawText(text, color = onActive, topLeft = Offset(tx, ty)) }
        clipRect(left = activeEnd) { drawText(text, color = cs.onSurface, topLeft = Offset(tx, ty)) }
    }
}

/**
 * Android 17 large QS tile: translucent dark pill with a 56dp icon container whose shape tells
 * the state (rounded square + primary = on, circle + neutral = off) and morphs between them
 * with the expressive spatial spring. Tap the icon to toggle, the label to open details.
 */
@Composable
fun QsTile(
    label: String,
    secondary: String?,
    active: Boolean,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onIconClick: (() -> Unit)? = null,
    toggleable: Boolean = true,
    enabled: Boolean = true,
) {
    val qs = qsColors()
    val motion = MaterialTheme.motionScheme
    val src = remember { MutableInteractionSource() }
    val iconSrc = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val iconPressed by iconSrc.collectIsPressedAsState()
    val tileCorner by animateDpAsState(if (pressed) 14.dp else 24.dp, motion.fastSpatialSpec(), label = "tileCorner")
    val iconCorner by animateFloatAsState(
        when {
            iconPressed -> 22f
            active -> 29f
            else -> 50f
        },
        motion.defaultSpatialSpec(), label = "iconCorner",
    )
    val iconScale by animateFloatAsState(if (iconPressed) 0.88f else 1f, motion.fastSpatialSpec(), label = "iconScale")
    val iconBg by animateColorAsState(
        when {
            active -> qs.iconActive
            toggleable -> qs.iconInactive
            else -> Color.Transparent
        },
        motion.defaultEffectsSpec(), label = "iconBg",
    )
    val iconFg by animateColorAsState(if (active) qs.onIconActive else qs.onIconInactive, motion.defaultEffectsSpec(), label = "iconFg")
    val alpha = if (enabled) 1f else 0.45f

    Row(
        modifier
            .height(72.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(tileCorner))
            .background(if (pressed) qs.containerPressed else qs.container)
            .clickable(enabled = enabled, interactionSource = src, indication = ripple(), onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
                .clip(RoundedCornerShape(CornerSize(iconCorner)))
                .background(iconBg)
                .clickable(enabled = enabled, interactionSource = iconSrc, indication = ripple(), onClick = onIconClick ?: onClick),
            contentAlignment = Alignment.Center,
        ) { icon(iconFg) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = qs.text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondary != null) {
                Text(
                    secondary,
                    color = qs.textSecondary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Telemetry tile: expressive wavy progress ring with the metric icon inside. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatTile(
    icon: ImageVector,
    label: String,
    value: String,
    sub: String,
    progress: Float,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val qs = qsColors()
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), spring(dampingRatio = 0.8f, stiffness = 120f), label = "stat")
    Row(
        modifier
            .height(72.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(qs.container)
            .padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
            CircularWavyProgressIndicator(
                progress = { p },
                modifier = Modifier.fillMaxSize(),
                color = accent,
                trackColor = accent.copy(alpha = 0.2f),
            )
            Icon(icon, null, Modifier.size(20.dp), tint = accent)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = qs.textSecondary, maxLines = 1)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
                color = qs.text,
                maxLines = 1,
            )
            Text(sub, style = MaterialTheme.typography.labelSmall, color = qs.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Android 17 status bar battery: rounded body with the level printed inside, bolt when charging. */
@Composable
fun BatteryPill(level: Int, charging: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val fill = when {
        charging -> Color(0xFF63CF79)
        level <= 15 -> cs.error
        else -> cs.onSurface
    }
    val textMeasurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 32.dp, height = 17.dp)) {
            val bodyW = size.width - 2.5.dp.toPx()
            val r = CornerRadius(5.dp.toPx())
            val split = bodyW * (level.coerceIn(0, 100) / 100f)
            drawRoundRect(cs.onSurface.copy(alpha = 0.3f), size = Size(bodyW, size.height), cornerRadius = r)
            clipRect(right = split) { drawRoundRect(fill, size = Size(bodyW, size.height), cornerRadius = r) }
            drawRoundRect(
                cs.onSurface.copy(alpha = 0.45f),
                topLeft = Offset(bodyW + 0.8.dp.toPx(), size.height * 0.32f),
                size = Size(1.7.dp.toPx(), size.height * 0.36f),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
            val t = textMeasurer.measure("$level", style)
            val o = Offset((bodyW - t.size.width) / 2f, (size.height - t.size.height) / 2f)
            clipRect(right = split) { drawText(t, color = cs.surface, topLeft = o) }
            clipRect(left = split) { drawText(t, color = cs.onSurface, topLeft = o) }
        }
        if (charging) Icon(Icons.Rounded.Bolt, null, Modifier.size(15.dp), tint = cs.onSurface)
    }
}

/** Three-blade fan that spins proportionally to the real fan duty. */
@Composable
fun FanGlyph(speed: Float, color: Color, modifier: Modifier = Modifier.size(26.dp)) {
    var angle by remember { mutableFloatStateOf(0f) }
    val sp by rememberUpdatedState(speed)
    LaunchedEffect(speed > 0.01f) {
        if (sp <= 0.01f) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) angle = (angle + (t - last) / 1e9f * (90f + 900f * sp)) % 360f
                last = t
            }
        }
    }
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val blade = Path().apply {
            moveTo(12f * s, 12f * s)
            cubicTo(10.4f * s, 9.2f * s, 8.6f * s, 5.4f * s, 10.9f * s, 3.7f * s)
            cubicTo(13.1f * s, 2.3f * s, 16.1f * s, 4.1f * s, 15.0f * s, 7.4f * s)
            cubicTo(14.4f * s, 9.4f * s, 12.9f * s, 10.9f * s, 12f * s, 12f * s)
            close()
        }
        rotate(angle) {
            repeat(3) { i -> rotate(i * 120f) { drawPath(blade, color) } }
        }
        drawCircle(color, radius = 2.4f * s)
    }
}

/** Ring split in four segments showing the current stick light colors. */
@Composable
fun LedRingGlyph(leds: LedState?, color: Color, modifier: Modifier = Modifier.size(26.dp)) {
    var hueShift by remember { mutableFloatStateOf(0f) }
    val rainbow = leds != null && leds.enabled && leds.mode == Proto.LED_RAINBOW
    LaunchedEffect(rainbow) {
        if (!rainbow) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) hueShift = (hueShift + (t - last) / 1e9f * 90f) % 360f
                last = t
            }
        }
    }
    Canvas(modifier) {
        val stroke = size.minDimension * 0.17f
        val inset = stroke / 2 + size.minDimension * 0.06f
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        for (i in 0 until 4) {
            val c = when {
                leds == null || !leds.enabled -> color.copy(alpha = 0.45f)
                rainbow -> Color.hsv((hueShift + i * 90f) % 360f, 0.85f, 1f)
                else -> Color(0xFF000000.toInt() or (if (i < 2) leds.left else leds.rightColor))
            }
            drawArc(
                c,
                startAngle = -90f + i * 90f + 9f,
                sweepAngle = 72f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}
