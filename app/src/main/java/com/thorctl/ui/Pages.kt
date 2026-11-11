package com.thorctl.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DesktopAccessDisabled
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thorctl.app.DaemonClient
import com.thorctl.shared.DaemonState
import com.thorctl.shared.FanPoint
import com.thorctl.shared.FanProfile
import com.thorctl.shared.GameState
import com.thorctl.shared.LedState
import com.thorctl.shared.Proto
import com.thorctl.shared.Stats
import kotlin.math.hypot

private val PagePadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)

/** Connected button group (M3 Expressive): selected segment morphs to a rounder shape. */
@Composable
private fun ConnectedChoice(options: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, label ->
            ToggleButton(
                checked = selected == i,
                onCheckedChange = { onSelect(i) },
                enabled = enabled,
                modifier = Modifier.weight(1f).height(48.dp),
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                if (selected == i) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(label, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Fan
// ---------------------------------------------------------------------------------------------

@Composable
fun FanPage(state: DaemonState?, stats: Stats?, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val qs = qsColors()
    val profiles = state?.profiles ?: FanProfile.BUILTINS
    val active = state?.activeProfile ?: FanProfile.BUILTINS.first()
    var draft by remember(active) { mutableStateOf(active) }

    Column(Modifier.fillMaxSize().padding(PagePadding)) {
        DetailHeader("Fan", onBack) {
            if (stats != null) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(cs.tertiaryContainer)
                        .padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FanGlyph(stats.fanPercent / 100f, cs.onTertiaryContainer, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${stats.fanTemp.toInt()}° · ${stats.fanPercent}%", style = MaterialTheme.typography.labelLarge, color = cs.onTertiaryContainer)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(profiles, key = { it.id }) { p ->
                val checked = p.id == active.id
                ToggleButton(
                    checked = checked,
                    onCheckedChange = { DaemonClient.setFanProfile(p.id) },
                    shapes = ToggleButtonShapes(ToggleButtonDefaults.shape, ToggleButtonDefaults.pressedShape, ToggleButtonDefaults.checkedShape),
                    modifier = Modifier.height(44.dp),
                ) {
                    if (checked) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(p.name)
                }
            }
            item(key = "_new") {
                OutlinedButton(onClick = { createCustom(profiles, active) }, modifier = Modifier.height(44.dp)) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(qs.container),
        ) {
            FanCurveEditor(
                profile = draft,
                editable = !draft.isBuiltin,
                liveTemp = stats?.fanTemp,
                livePct = stats?.fanPercent,
                onChange = { draft = it },
                onCommit = { DaemonClient.saveFanProfile(it) },
            )
            if (draft.isAuto) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("System control", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                    Text("LineageOS thermal zones drive the fan", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (draft.isBuiltin) draft.shortDescription() else "Drag the points to shape the curve",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (!draft.isBuiltin) {
                TextButton(onClick = { DaemonClient.deleteFanProfile(draft.id) }) {
                    Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
                Spacer(Modifier.width(6.dp))
            }
            if (!draft.isAuto) {
                FilledTonalButton(onClick = { createCustom(profiles, draft) }) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (draft.isBuiltin) "Customize" else "Duplicate")
                }
            }
        }
    }
}

private fun createCustom(all: List<FanProfile>, base: FanProfile) {
    val src = if (base.isAuto) FanProfile.BUILTINS.first { it.id == "smart" } else base
    var n = all.count { !it.isBuiltin } + 1
    while (all.any { it.name == "Custom $n" }) n++
    val p = FanProfile("custom-" + System.currentTimeMillis().toString(36), "Custom $n", src.points)
    DaemonClient.saveFanProfile(p, activate = true)
}

private const val T_MIN = 25f
private const val T_MAX = 95f

@Composable
fun FanCurveEditor(
    profile: FanProfile,
    editable: Boolean,
    liveTemp: Float?,
    livePct: Int?,
    onChange: (FanProfile) -> Unit,
    onCommit: (FanProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val tm = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val cur by rememberUpdatedState(profile)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    var dragIndex by remember { mutableIntStateOf(-1) }
    val pointGrow by animateFloatAsState(if (dragIndex >= 0) 1f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "grow")

    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(editable) {
                if (!editable) return@pointerInput
                val left = 44.dp.toPx()
                val right = 20.dp.toPx()
                val top = 20.dp.toPx()
                val bottom = 30.dp.toPx()
                val pw = size.width - left - right
                val ph = size.height - top - bottom
                fun px(t: Float) = left + (t - T_MIN) / (T_MAX - T_MIN) * pw
                fun py(p: Float) = top + (1f - p / 100f) * ph
                detectDragGestures(
                    onDragStart = { pos ->
                        val pts = cur.points
                        val (i, d) = pts.indices.map { it to hypot(px(pts[it].temp) - pos.x, py(pts[it].pct) - pos.y) }
                            .minByOrNull { it.second } ?: (-1 to Float.MAX_VALUE)
                        dragIndex = if (d < 40.dp.toPx()) i else -1
                    },
                    onDragEnd = {
                        if (dragIndex >= 0) commit(cur)
                        dragIndex = -1
                    },
                    onDragCancel = { dragIndex = -1 },
                    onDrag = { c, _ ->
                        val i = dragIndex
                        if (i < 0) return@detectDragGestures
                        c.consume()
                        val pts = cur.points.toMutableList()
                        val lo = if (i > 0) pts[i - 1].temp + 2f else T_MIN
                        val hi = if (i < pts.size - 1) pts[i + 1].temp - 2f else T_MAX
                        val t = (T_MIN + (c.position.x - left) / pw * (T_MAX - T_MIN)).coerceIn(lo, hi)
                        val p = ((1f - (c.position.y - top) / ph) * 100f).coerceIn(0f, 100f)
                        pts[i] = FanPoint(Math.round(t).toFloat(), Math.round(p).toFloat())
                        change(cur.copy(points = pts))
                    },
                )
            },
    ) {
        val left = 44.dp.toPx()
        val right = 20.dp.toPx()
        val top = 20.dp.toPx()
        val bottom = 30.dp.toPx()
        val pw = size.width - left - right
        val ph = size.height - top - bottom
        fun px(t: Float) = left + (t - T_MIN) / (T_MAX - T_MIN) * pw
        fun py(p: Float) = top + (1f - p / 100f) * ph
        val grid = cs.outlineVariant.copy(alpha = 0.45f)

        for (p in 0..100 step 25) {
            drawLine(grid, Offset(left, py(p.toFloat())), Offset(left + pw, py(p.toFloat())), 1.dp.toPx())
            val l = tm.measure("$p%", labelStyle)
            drawText(l, cs.onSurfaceVariant, Offset(left - l.size.width - 8.dp.toPx(), py(p.toFloat()) - l.size.height / 2))
        }
        for (t in 30..90 step 10) {
            drawLine(grid, Offset(px(t.toFloat()), top), Offset(px(t.toFloat()), top + ph), 1.dp.toPx())
            val l = tm.measure("$t°", labelStyle)
            drawText(l, cs.onSurfaceVariant, Offset(px(t.toFloat()) - l.size.width / 2, top + ph + 6.dp.toPx()))
        }

        val pts = profile.points.sortedBy { it.temp }
        if (pts.isNotEmpty()) {
            val line = Path().apply {
                moveTo(px(T_MIN), py(pts.first().pct))
                pts.forEach { lineTo(px(it.temp.coerceIn(T_MIN, T_MAX)), py(it.pct)) }
                lineTo(px(T_MAX), py(pts.last().pct))
            }
            val area = Path().apply {
                addPath(line)
                lineTo(px(T_MAX), top + ph)
                lineTo(px(T_MIN), top + ph)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(cs.primary.copy(alpha = 0.32f), cs.primary.copy(alpha = 0.02f)), startY = top, endY = top + ph))
            drawPath(line, cs.primary, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            pts.forEachIndexed { i, p ->
                val c = Offset(px(p.temp), py(p.pct))
                if (editable) {
                    val r = 8.dp.toPx() + if (i == dragIndex) 5.dp.toPx() * pointGrow else 0f
                    drawCircle(cs.surfaceContainerHigh, r + 3.dp.toPx(), c)
                    drawCircle(cs.primary, r, c)
                    if (i == dragIndex) {
                        val l = tm.measure("${p.temp.toInt()}° → ${p.pct.toInt()}%", labelStyle.copy(fontWeight = FontWeight.SemiBold))
                        drawText(l, cs.onSurface, Offset((c.x - l.size.width / 2).coerceIn(left, left + pw - l.size.width), c.y - r - l.size.height - 6.dp.toPx()))
                    }
                } else {
                    drawCircle(cs.primary, 4.5.dp.toPx(), c)
                }
            }
        }

        if (liveTemp != null && liveTemp > 0f) {
            val x = px(liveTemp.coerceIn(T_MIN, T_MAX))
            drawLine(
                cs.tertiary, Offset(x, top), Offset(x, top + ph), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
            )
            if (livePct != null) {
                val c = Offset(x, py(livePct.toFloat()))
                drawCircle(cs.surfaceContainerHigh, 9.dp.toPx(), c)
                drawCircle(cs.tertiary, 6.dp.toPx(), c)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Lights
// ---------------------------------------------------------------------------------------------

private val Swatches = listOf(
    0xFF3B30, 0xFF9500, 0xFFCC00, 0x34C759, 0x00C7BE, 0x4FC3F7, 0x0A84FF, 0x5E5CE6, 0xBF5AF2, 0xFF2D92, 0xFFFFFF,
)

@Composable
fun LightsPage(state: DaemonState?, onBack: () -> Unit) {
    val leds = state?.leds ?: LedState()
    var target by remember { mutableIntStateOf(if (leds.sync) 0 else 1) }
    val colorEditable = leds.mode != Proto.LED_RAINBOW
    val currentColor = if (target == 2) leds.rightColor else leds.left

    fun setColor(rgb: Int) {
        val c = rgb and 0xFFFFFF
        DaemonClient.setLeds(
            when (target) {
                0 -> leds.copy(left = c, right = c, sync = true)
                1 -> leds.copy(left = c, right = leds.rightColor, sync = false)
                else -> leds.copy(right = c, sync = false)
            },
        )
    }

    Column(Modifier.fillMaxSize().padding(PagePadding)) {
        DetailHeader("Stick lights", onBack) {
            Switch(
                checked = leds.enabled,
                onCheckedChange = { DaemonClient.setLeds(leds.copy(enabled = it)) },
                thumbContent = {
                    Icon(
                        if (leds.enabled) Icons.Rounded.Check else Icons.Rounded.Close,
                        null,
                        Modifier.size(SwitchDefaults.IconSize),
                    )
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        val modes = listOf(Proto.LED_SOLID, Proto.LED_BREATHE, Proto.LED_RAINBOW)
        ConnectedChoice(listOf("Solid", "Breathe", "Rainbow"), modes.indexOf(leds.mode).coerceAtLeast(0)) { i ->
            DaemonClient.setLeds(leds.copy(mode = modes[i], enabled = true))
        }
        Spacer(Modifier.height(8.dp))
        ConnectedChoice(listOf("Both sticks", "Left", "Right"), target, enabled = colorEditable) { i ->
            target = i
            if (i == 0 && !leds.sync) DaemonClient.setLeds(leds.copy(sync = true, right = leds.left))
            if (i != 0 && leds.sync) DaemonClient.setLeds(leds.copy(sync = false, right = leds.left))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Swatches.forEach { rgb ->
                Swatch(rgb, selected = (currentColor and 0xFFFFFF) == rgb, enabled = colorEditable) { setColor(rgb) }
            }
        }
        Spacer(Modifier.height(10.dp))
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(0xFF000000.toInt() or currentColor, it) }
        QsSlider(
            value = hsv[0] / 360f,
            onValueChange = { setColor(android.graphics.Color.HSVToColor(floatArrayOf(it * 360f, 1f, 1f))) },
            onValueChangeFinished = {},
            icon = Icons.Rounded.Palette,
            label = "Color",
            enabled = colorEditable,
            trackBrush = Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }),
        )
        Spacer(Modifier.height(4.dp))
        QsSlider(
            value = leds.brightness / 255f,
            onValueChange = { DaemonClient.setLeds(leds.copy(brightness = (it * 255).toInt().coerceAtLeast(4))) },
            onValueChangeFinished = {},
            icon = Icons.Rounded.Lightbulb,
            label = "Brightness",
            enabled = leds.enabled,
        )
        Spacer(Modifier.height(4.dp))
        QsSlider(
            value = (leds.speed - 0.25f) / 2.75f,
            onValueChange = { DaemonClient.setLeds(leds.copy(speed = 0.25f + it * 2.75f)) },
            onValueChangeFinished = {},
            icon = Icons.Rounded.Speed,
            label = "Effect speed",
            enabled = leds.enabled && leds.mode != Proto.LED_SOLID,
        )
    }
}

/** Color swatch: a circle that morphs into a 9-sided cookie (and turns) when selected. */
@Composable
private fun Swatch(rgb: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val morph = rememberMorph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided)
    val p by animateFloatAsState(if (selected) 1f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "swatch")
    val color = Color(0xFF000000.toInt() or rgb).copy(alpha = if (enabled) 1f else 0.3f)
    Box(
        Modifier
            .size(40.dp)
            .graphicsLayer {
                rotationZ = p * 40f
                val s = 0.82f + 0.18f * p
                scaleX = s
                scaleY = s
            }
            .clip(MorphShape(morph, p))
            .background(color)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            val onColor = if (rgb == 0xFFFFFF || rgb == 0xFFCC00 || rgb == 0x4FC3F7 || rgb == 0x00C7BE) Color.Black else Color.White
            Icon(Icons.Rounded.Check, null, Modifier.size(18.dp).graphicsLayer { rotationZ = -p * 40f }, tint = onColor.copy(alpha = p))
        } else if (rgb == 0xFFFFFF || rgb == 0x000000) {
            Box(Modifier.size(40.dp).clip(MorphShape(morph, 0f)).background(cs.outline.copy(alpha = 0.15f)))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Controller routing
// ---------------------------------------------------------------------------------------------

@Composable
fun ControllerPage(state: DaemonState?, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val cur = state?.controller ?: Proto.TARGET_FREE
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding)) {
        DetailHeader("Controller input", onBack)
        Spacer(Modifier.height(4.dp))
        Text("Choose which screen receives the built-in controller.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        OptionTile(Icons.Rounded.TouchApp, "Follow focus", "Goes to whichever screen you touched last", cur == Proto.TARGET_FREE) {
            DaemonClient.setController(Proto.TARGET_FREE)
        }
        Spacer(Modifier.height(8.dp))
        OptionTile(Icons.Rounded.VerticalAlignTop, "Top screen", "Always control the app on the top screen", cur == Proto.TARGET_TOP) {
            DaemonClient.setController(Proto.TARGET_TOP)
        }
        Spacer(Modifier.height(8.dp))
        OptionTile(Icons.Rounded.VerticalAlignBottom, "Bottom screen", "Always control the app on the bottom screen", cur == Proto.TARGET_BOTTOM) {
            DaemonClient.setController(Proto.TARGET_BOTTOM)
        }
        Spacer(Modifier.height(16.dp))
        ShortcutList()
    }
}

/** Selectable row in the QS tile style; the icon container morphs circle -> rounded square. */
@Composable
private fun OptionTile(icon: ImageVector, title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    val qs = qsColors()
    val motion = MaterialTheme.motionScheme
    val corner by animateFloatAsState(if (selected) 29f else 50f, motion.defaultSpatialSpec(), label = "optCorner")
    val iconBg by animateColorAsState(if (selected) qs.iconActive else qs.iconInactive, motion.defaultEffectsSpec(), label = "optBg")
    val iconFg by animateColorAsState(if (selected) qs.onIconActive else qs.onIconInactive, motion.defaultEffectsSpec(), label = "optFg")
    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(qs.container)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(CornerSize(corner))).background(iconBg),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = iconFg) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium), color = qs.text)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = qs.textSecondary)
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

/** AYN-button shortcut reference. */
@Composable
fun ShortcutList() {
    val cs = MaterialTheme.colorScheme
    val rows = listOf(
        "AYN + ▲" to "Controller → top screen",
        "AYN + ▼" to "Controller → bottom screen",
        "AYN + ◀ / ▶" to "Controller follows focus",
        "AYN + B" to "Move top app to bottom screen",
        "AYN + X" to "Move bottom app to top screen",
        "AYN + Y" to "Game mode on / off",
        "AYN + L1 / R1" to "Bottom screen dimmer / brighter",
        "AYN + L2 / R2" to "Top screen dimmer / brighter",
        "Hold Home" to "Recent apps",
    )
    Column {
        rows.forEach { (keys, what) ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    keys,
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSecondaryContainer,
                    modifier = Modifier
                        .width(124.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(cs.secondaryContainer)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.width(12.dp))
                Text(what, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Game mode
// ---------------------------------------------------------------------------------------------

/** Launcher label of a package ("" stays ""). */
@Composable
fun rememberAppLabel(pkg: String): String {
    val ctx = LocalContext.current
    return remember(pkg) {
        if (pkg.isEmpty()) {
            ""
        } else {
            try {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg
            }
        }
    }
}

@Composable
fun GamePage(state: DaemonState?, stats: Stats?, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val g = state?.game ?: GameState()
    val o = g.options
    val enabled = state != null
    val label = rememberAppLabel(g.pkg)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding)) {
        DetailHeader("Game mode", onBack) {
            Switch(
                checked = g.enabled,
                onCheckedChange = { DaemonClient.setGameMode(it) },
                enabled = enabled,
                thumbContent = {
                    Icon(if (g.enabled) Icons.Rounded.Check else Icons.Rounded.Close, null, Modifier.size(SwitchDefaults.IconSize))
                },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Gives the game on the top screen the whole device. Turn it on before or during a game, or press AYN + Y.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        val (title, desc) = when {
            !g.enabled -> "Off" to "The next game you open gets boosted once this is on"
            g.pkg.isEmpty() -> "Waiting for a game" to "Open a game on the top screen"
            !g.boosted -> "$label · paused" to "Boost comes back when the screen turns on"
            else -> label to buildString {
                append("Boosting")
                if (g.freedMb >= 100) append(" · ${"%.1f".format(g.freedMb / 1024f)} GB freed")
                stats?.let { append(" · ${"%.1f".format(it.ramAvailMb / 1024f)} GB free now") }
            }
        }
        StatusTile(Icons.Rounded.RocketLaunch, title, desc, active = g.enabled && g.pkg.isNotEmpty())

        Spacer(Modifier.height(16.dp))
        Text("While a game runs", style = MaterialTheme.typography.titleSmall, color = cs.primary)
        Spacer(Modifier.height(8.dp))
        SwitchTile(
            Icons.Rounded.CleaningServices, "Close other apps",
            "Closes every other app when the game starts and keeps background apps closed",
            o.closeApps, enabled,
        ) { DaemonClient.setGameOptions(o.copy(closeApps = it)) }
        Spacer(Modifier.height(8.dp))
        SwitchTile(
            Icons.Rounded.DesktopAccessDisabled, "Turn off the bottom screen",
            "While the game is in front, so the launcher there frees its memory. AYN turns it back on",
            o.bottomOff, enabled,
        ) { DaemonClient.setGameOptions(o.copy(bottomOff = it)) }
        Spacer(Modifier.height(8.dp))
        SwitchTile(Icons.Rounded.Memory, "CPU at full speed", "Every core held at its top clock", o.cpu, enabled) {
            DaemonClient.setGameOptions(o.copy(cpu = it))
        }
        Spacer(Modifier.height(8.dp))
        SwitchTile(Icons.Rounded.DeveloperBoard, "GPU at full speed", "GPU held at its top clock", o.gpu, enabled) {
            DaemonClient.setGameOptions(o.copy(gpu = it))
        }
        Spacer(Modifier.height(16.dp))
        Text("Fan while gaming", style = MaterialTheme.typography.titleSmall, color = cs.primary)
        Spacer(Modifier.height(8.dp))
        val fanIds = listOf("", "sport", "max")
        ConnectedChoice(listOf("Unchanged", "Sport", "Max"), selected = fanIds.indexOf(o.fan).coerceAtLeast(0), enabled = enabled) {
            DaemonClient.setGameOptions(o.copy(fan = fanIds[it]))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Clocks still drop if the chip runs too hot. Everything goes back to normal while the screen is off and when game mode ends.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusTile(icon: ImageVector, title: String, desc: String, active: Boolean) {
    val qs = qsColors()
    val motion = MaterialTheme.motionScheme
    val corner by animateFloatAsState(if (active) 29f else 50f, motion.defaultSpatialSpec(), label = "gameCorner")
    val iconBg by animateColorAsState(if (active) qs.iconActive else qs.iconInactive, motion.defaultEffectsSpec(), label = "gameBg")
    val iconFg by animateColorAsState(if (active) qs.onIconActive else qs.onIconInactive, motion.defaultEffectsSpec(), label = "gameFg")
    Row(
        Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(24.dp)).background(qs.container).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(CornerSize(corner))).background(iconBg), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = iconFg)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium), color = qs.text, maxLines = 1)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = qs.textSecondary, maxLines = 2)
        }
    }
}

@Composable
private fun SwitchTile(icon: ImageVector, title: String, desc: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val qs = qsColors()
    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(qs.container)
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(start = 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = qs.textSecondary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium), color = qs.text, maxLines = 1)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = qs.textSecondary, maxLines = 2)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            thumbContent = { Icon(if (checked) Icons.Rounded.Check else Icons.Rounded.Close, null, Modifier.size(SwitchDefaults.IconSize)) },
        )
    }
}
