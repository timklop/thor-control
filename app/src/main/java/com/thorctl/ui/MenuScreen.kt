package com.thorctl.ui

import com.thorctl.shared.BrightnessCurve

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.DesktopAccessDisabled
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorctl.app.DaemonClient
import com.thorctl.shared.DaemonState
import com.thorctl.shared.FanProfile
import com.thorctl.shared.Proto
import com.thorctl.shared.Stats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.util.Date
import java.util.Locale

enum class Page(val depth: Int) { MAIN(0), FAN(1), LIGHTS(1), CONTROLLER(1), GAME(1), GAMEPAD(2) }

private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/**
 * Full-screen overlay content. Opening: the window blur and tint ramp up (emphasized
 * decelerate) while the panel rows drop in one after another on expressive springs, like the
 * shade being pulled down. Closing reverses it quickly.
 */
@Composable
fun MenuOverlay(
    visible: StateFlow<Boolean>,
    blur: Boolean,
    onReveal: (Float) -> Unit,
    onExitFinished: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val vis by visible.collectAsState()
    val reveal = remember { Animatable(0f) }
    val exitDone by rememberUpdatedState(onExitFinished)
    val revealCb by rememberUpdatedState(onReveal)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(vis) {
        if (vis) {
            shown = true
            reveal.animateTo(1f, tween(260, easing = EmphasizedDecelerate))
        } else if (shown) {
            reveal.animateTo(0f, tween(170, easing = EmphasizedAccelerate))
            exitDone()
        }
    }
    LaunchedEffect(Unit) { snapshotFlow { reveal.value }.collect { revealCb(it) } }

    val qs = qsColors()
    val scrim = if (blur) qs.scrim else MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.95f)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = reveal.value }
                .background(scrim)
                // Taps on the backdrop are swallowed; the menu closes only with the AYN button.
                .pointerInput(Unit) { detectTapGestures { } },
        )
        MenuPanel(entered = vis, onOpenApp = onOpenApp, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun MenuPanel(entered: Boolean, onOpenApp: (() -> Unit)?, modifier: Modifier = Modifier) {
    val state by DaemonClient.state.collectAsState()
    val stats by DaemonClient.stats.collectAsState()
    val connected by DaemonClient.connected.collectAsState()
    var page by remember { mutableStateOf(Page.MAIN) }

    AnimatedContent(
        targetState = page,
        modifier = modifier,
        transitionSpec = {
            if (targetState.depth > initialState.depth) {
                (slideInHorizontally(spring(dampingRatio = 0.8f, stiffness = 420f)) { it / 6 } +
                    scaleIn(spring(dampingRatio = 0.8f, stiffness = 420f), initialScale = 0.94f) +
                    fadeIn(tween(160)))
                    .togetherWith(fadeOut(tween(110)) + scaleOut(tween(140), targetScale = 0.97f))
            } else {
                fadeIn(tween(120)).togetherWith(
                    slideOutHorizontally(tween(180, easing = EmphasizedAccelerate)) { it / 6 } + fadeOut(tween(150)),
                )
            }
        },
        label = "page",
    ) { p ->
        val back = { page = Page.MAIN }
        when (p) {
            Page.MAIN -> MainPage(state, stats, connected, entered, onNavigate = { page = it }, onOpenApp = onOpenApp)
            Page.FAN -> FanPage(state, stats, onBack = back)
            Page.LIGHTS -> LightsPage(state, onBack = back)
            Page.CONTROLLER -> ControllerPage(state, onBack = back, onGamepad = { page = Page.GAMEPAD })
            Page.GAMEPAD -> GamepadPage(state, onBack = { page = Page.CONTROLLER })
            Page.GAME -> GamePage(state, stats, onBack = back)
        }
    }
}

@Composable
private fun MainPage(
    state: DaemonState?,
    stats: Stats?,
    connected: Boolean,
    entered: Boolean,
    onNavigate: (Page) -> Unit,
    onOpenApp: (() -> Unit)?,
) {
    val enabled = connected && state != null
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
        Header(stats, connected, enabled, onOpenApp, Modifier.enter(0, entered))
        Spacer(Modifier.height(14.dp))

        QsSlider(
            value = BrightnessCurve.toPosition(state?.brightnessTop ?: 0.5f),
            onValueChange = { DaemonClient.setBrightness("top", BrightnessCurve.toLinear(it), false) },
            onValueChangeFinished = { DaemonClient.setBrightness("top", BrightnessCurve.toLinear(it), true) },
            icon = Icons.Rounded.BrightnessHigh,
            label = "Top screen",
            enabled = enabled,
            modifier = Modifier.enter(1, entered),
        )
        Spacer(Modifier.height(6.dp))
        QsSlider(
            value = BrightnessCurve.toPosition(state?.brightnessBottom ?: 0.5f),
            onValueChange = { DaemonClient.setBrightness("bottom", BrightnessCurve.toLinear(it), false) },
            onValueChangeFinished = { DaemonClient.setBrightness("bottom", BrightnessCurve.toLinear(it), true) },
            icon = Icons.Rounded.BrightnessLow,
            label = "Bottom screen",
            enabled = enabled,
            modifier = Modifier.enter(2, entered),
        )
        Spacer(Modifier.height(14.dp))

        val profile = state?.runningProfile
        val fanByGame = state != null && state.fanOverride.isNotEmpty()
        val fanPct = stats?.fanPercent ?: 0
        val game = state?.game
        val gameLabel = rememberAppLabel(game?.pkg.orEmpty())
        val leds = state?.leds
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QsTile(
                label = "Fan",
                secondary = when {
                    profile == null -> "Unavailable"
                    fanByGame -> "${profile.name} (game) · $fanPct%"
                    else -> "${profile.name} · $fanPct%"
                },
                active = profile != null && !profile.isAuto,
                icon = { FanGlyph(speed = fanPct / 100f, color = it) },
                onClick = { onNavigate(Page.FAN) },
                onIconClick = { state?.let { cycleFan(it) } },
                enabled = enabled,
                modifier = Modifier.weight(1f).enter(3, entered),
            )
            val gamepad = state?.gamepad
            QsTile(
                label = "Controller",
                secondary = when {
                    gamepad?.on != true -> controllerLabel(state?.controller)
                    gamepad.connected -> "Gamepad · connected"
                    else -> "Gamepad · waiting"
                },
                active = state != null && (state.controller != Proto.TARGET_FREE || state.gamepad.on),
                icon = { Icon(Icons.Rounded.SportsEsports, null, tint = it) },
                onClick = { onNavigate(Page.CONTROLLER) },
                onIconClick = { state?.let { DaemonClient.setController(nextTarget(it.controller)) } },
                enabled = enabled,
                modifier = Modifier.weight(1f).enter(4, entered),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QsTile(
                label = "Stick lights",
                secondary = when {
                    leds == null -> "Unavailable"
                    !leds.enabled -> "Off"
                    else -> leds.mode.replaceFirstChar { it.uppercase() }
                },
                active = leds?.enabled == true,
                icon = { LedRingGlyph(leds, it) },
                onClick = { onNavigate(Page.LIGHTS) },
                onIconClick = { leds?.let { DaemonClient.setLeds(it.copy(enabled = !it.enabled)) } },
                enabled = enabled,
                modifier = Modifier.weight(1f).enter(5, entered),
            )
            QsTile(
                label = "Game mode",
                secondary = when {
                    game == null -> "Unavailable"
                    !game.enabled -> "Off"
                    game.pkg.isEmpty() -> "Waiting for a game"
                    !game.boosted -> "$gameLabel · paused"
                    else -> gameLabel
                },
                active = game?.enabled == true,
                icon = { Icon(Icons.Rounded.RocketLaunch, null, tint = it) },
                onClick = { onNavigate(Page.GAME) },
                onIconClick = { game?.let { DaemonClient.setGameMode(!it.enabled) } },
                enabled = enabled,
                modifier = Modifier.weight(1f).enter(6, entered),
            )
        }
        Spacer(Modifier.height(8.dp))
        StatsGrid(stats, entered)
    }
}

private fun cycleFan(s: DaemonState) {
    val ids = s.profiles.map { it.id }
    val next = ids[(ids.indexOf(s.fanProfile) + 1).mod(ids.size)]
    DaemonClient.setFanProfile(next)
}

private fun nextTarget(t: String) = when (t) {
    Proto.TARGET_FREE -> Proto.TARGET_TOP
    Proto.TARGET_TOP -> Proto.TARGET_BOTTOM
    else -> Proto.TARGET_FREE
}

fun controllerLabel(t: String?) = when (t) {
    Proto.TARGET_TOP -> "Top screen"
    Proto.TARGET_BOTTOM -> "Bottom screen"
    null -> "Unavailable"
    else -> "Follows focus"
}

/** Status-bar style header, as at the top of the Android 17 shade. */
@Composable
private fun Header(stats: Stats?, connected: Boolean, enabled: Boolean, onOpenApp: (() -> Unit)?, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    val battery by produceState(readBattery(ctx), stats) {
        while (true) {
            value = readBattery(ctx)
            delay(5000)
        }
    }
    val is24 = DateFormat.is24HourFormat(ctx)
    val time = DateFormat.format(if (is24) "HH:mm" else "h:mm", now).toString()
    val date = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd"), now).toString()
    val textStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp)

    Row(modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(time, style = textStyle.copy(fontWeight = FontWeight.Medium), color = cs.onSurface)
        Spacer(Modifier.width(10.dp))
        Text(date, style = textStyle, color = cs.onSurface)
        Spacer(Modifier.weight(1f))
        if (!connected) {
            Text(
                "Service offline",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onErrorContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(cs.errorContainer)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        BatteryPill(battery.first, battery.second)
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = { DaemonClient.setBottomPower(false) }, enabled = enabled, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.DesktopAccessDisabled, "Turn off bottom screen", tint = cs.onSurface, modifier = Modifier.size(20.dp))
        }
        if (onOpenApp != null) {
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = onOpenApp, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.Settings, "Open Thor Control", tint = cs.onSurface, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun readBattery(ctx: Context): Pair<Int, Boolean> {
    val i: Intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0 to false
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
    val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
    return level to (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL &&
        i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
}

/** Telemetry as two more rows of the tile grid (wavy rings instead of icon containers). */
@Composable
private fun StatsGrid(stats: Stats?, entered: Boolean) {
    val cs = MaterialTheme.colorScheme
    val powerAccent = when {
        stats == null -> cs.primary
        stats.charging || stats.plugged -> Color(0xFF63CF79)
        stats.batteryLevel <= 15 -> cs.error
        else -> Color(0xFFF2C46B)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(
            Icons.Rounded.Memory, "CPU",
            value = stats?.let { "${(it.cpuLoad * 100).toInt()}%" } ?: "–",
            sub = stats?.let { "${"%.2f".format(it.cpuFreqMhz / 1000f)} GHz · ${it.cpuTemp.toInt()} °C" } ?: "",
            progress = stats?.cpuLoad ?: 0f, accent = cs.primary,
            modifier = Modifier.weight(1f).enter(7, entered),
        )
        StatTile(
            Icons.Rounded.DeveloperBoard, "GPU",
            value = stats?.let { "${(it.gpuLoad * 100).toInt()}%" } ?: "–",
            sub = stats?.let { "${it.gpuFreqMhz} MHz · ${it.gpuTemp.toInt()} °C" } ?: "",
            progress = stats?.gpuLoad ?: 0f, accent = cs.tertiary,
            modifier = Modifier.weight(1f).enter(8, entered),
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val ramFrac = stats?.let { if (it.ramTotalMb > 0) (it.ramTotalMb - it.ramAvailMb).toFloat() / it.ramTotalMb else 0f } ?: 0f
        StatTile(
            Icons.Rounded.Storage, "Memory",
            value = stats?.let { "${"%.1f".format((it.ramTotalMb - it.ramAvailMb) / 1024f)} GB" } ?: "–",
            sub = stats?.let { "of ${"%.0f".format(it.ramTotalMb / 1024f)} GB · ${(ramFrac * 100).toInt()}% used" } ?: "",
            progress = ramFrac,
            accent = cs.secondary,
            modifier = Modifier.weight(1f).enter(9, entered),
        )
        StatTile(
            Icons.Rounded.Bolt, "Power",
            value = stats?.let { powerValue(it) } ?: "–",
            sub = stats?.let { powerSub(it) } ?: "",
            progress = (stats?.batteryLevel ?: 0) / 100f, accent = powerAccent,
            modifier = Modifier.weight(1f).enter(10, entered),
        )
    }
}

private fun powerValue(s: Stats): String = when {
    s.charging -> "+${"%.1f".format(s.batteryWatts)} W"
    s.batteryWatts > 0.05f -> "${"%.1f".format(s.batteryWatts)} W"
    s.plugged -> "${"%.1f".format(s.inputWatts)} W"
    else -> "0.0 W"
}

private fun powerSub(s: Stats): String = when {
    s.charging -> "Charging · ${s.batteryLevel}% · in ${"%.1f".format(s.inputWatts)} W"
    s.plugged -> "Plugged in · ${s.batteryLevel}%"
    else -> "On battery · ${s.batteryLevel}% · ${s.batteryTemp.toInt()} °C"
}

/** Shared header for detail pages: tonal back button (morphs on press) + title. */
@Composable
fun DetailHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp),
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
        }
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium), color = cs.onSurface)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

fun FanProfile.shortDescription(): String = when (id) {
    FanProfile.AUTO_ID -> "LineageOS thermal control"
    "quiet" -> "Low noise, runs warmer"
    "smart" -> "Balanced"
    "sport" -> "Cool and loud"
    "max" -> "Full speed"
    else -> "Custom curve"
}
