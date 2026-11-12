package com.thorctl.daemon

import android.system.Os
import android.view.KeyEvent
import com.thorctl.shared.BrightnessCurve
import com.thorctl.shared.DaemonState
import com.thorctl.shared.FanPoint
import com.thorctl.shared.FanProfile
import com.thorctl.shared.GameOptions
import com.thorctl.shared.LedState
import com.thorctl.shared.Proto
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal class Daemon : InputWatcher.Listener {
    private val cfg = ConfigStore(File("/data/adb/thorctl/config.json"))
    private val fan = FanController()
    private val leds = LedController()
    private val displays = DisplayController()
    private val router = InputRouter(displays)
    private val stats = StatsCollector(fan)
    private val server = SocketServer(::isAllowedUid, ::handle)
    private val input = InputWatcher(this)
    private val game = GameMode(
        displays,
        onChanged = { broadcastState() },
        onStarted = { pkg, freed ->
            hud(JSONObject().put("kind", "game").put("state", "started").put("pkg", pkg).put("freedMb", freed), displays.topId)
        },
        onBottomOff = { hud(JSONObject().put("kind", "game").put("state", "bottom_off"), displays.topId) },
    )
    private val recents = Recents(
        displays, input,
        send = { server.broadcast(it) },
        hasClient = { server.hasClients },
        onHomeShown = { nudgeGame() },
    )
    private val gamepad = Gamepad(
        input,
        sendToApp = { server.broadcast(it) },
        onChanged = { broadcastState() },
        setDark = { displays.setGamepadDark(it) },
        keepAwake = {
            for (id in intArrayOf(displays.topId, displays.bottomId)) {
                if (id < 0) continue
                try {
                    SystemServices.userActivity(id)
                } catch (e: Throwable) {
                    logw("gamepad: user activity on display $id failed", e)
                }
            }
        },
        wakeUp = { SystemServices.injectKey(KeyEvent.KEYCODE_WAKEUP, displays.topId) },
        isInteractive = { SystemServices.isInteractive() },
    )
    private val sched = Executors.newScheduledThreadPool(2) { Thread(it, "sched") }

    @Volatile
    private var appUid = -1

    /** Thor Keyboard is on screen: L1 / R1 act as its shift key. */
    @Volatile
    private var keyboardVisible = false
    private var lastInteractive = true
    private var tickCount = 0L
    private var saveTask: ScheduledFuture<*>? = null

    fun run() {
        while (!SystemServices.servicesReady()) {
            logi("waiting for system services")
            Thread.sleep(1000)
        }
        cfg.load()
        displays.refresh()
        game.init(cfg.gameOptions)
        gamepad.init(cfg.gamepadTransport, cfg.gamepadScreensOff)
        displays.setBottomTouch(true) // never leave touch disabled from a previous crash

        leds.apply(cfg.leds)
        leds.start()
        router.apply(cfg.controller)

        every(1000) { fanTick() }
        every(1000) { statsTick() }
        every(2000) { housekeeping() }
        // AynParts restores its own light/focus prefs on BOOT_COMPLETED; win that race.
        sched.schedule({ reapply() }, 20, TimeUnit.SECONDS)

        input.start()
        logi("daemon ready (fan=${fan.available} leds=${leds.available} top=${displays.topId} bottom=${displays.bottomId})")
        server.serve()
    }

    private fun every(ms: Long, block: () -> Unit) {
        sched.scheduleWithFixedDelay({
            try {
                block()
            } catch (e: Throwable) {
                logw("periodic task failed", e)
            }
        }, ms, ms, TimeUnit.MILLISECONDS)
    }

    // ---- periodic -------------------------------------------------------------------------

    @Synchronized
    private fun fanTick() {
        val override = game.fanOverride?.let { cfg.profile(it) }
        fan.tick(override ?: cfg.profile(cfg.fanProfile) ?: FanProfile.BUILTINS.first(), screenOn = SystemServices.isInteractive())
    }

    private fun statsTick() {
        if (!server.hasStatsSubscribers) return
        server.broadcastStats(stats.collect().toJson().toString())
    }

    private fun housekeeping() {
        tickCount++
        val interactive = SystemServices.isInteractive()
        if (interactive != lastInteractive) {
            lastInteractive = interactive
            leds.setAwake(interactive)
            if (interactive) displays.onWake()
            if (interactive && (displays.bottomForcedOff || displays.gamepadDark)) {
                // Waking powers every display back on; hold ours off again.
                for (delay in longArrayOf(150, 600, 1500)) sched.schedule({ displays.reassertOverrides() }, delay, TimeUnit.MILLISECONDS)
            }
        }
        if (interactive) displays.healBottomPanel()
        if (recents.isOpen && (!interactive || !server.hasClients)) recents.close()
        if (!server.hasClients) keyboardVisible = false
        game.tick(interactive)
        gamepad.tick()
        if (tickCount % 5 == 0L) {
            val before = displays.bottomId
            displays.refresh()
            if (before != displays.bottomId) broadcastState()
        }
        if (tickCount % 15 == 1L && cfg.keepAccessibility) ensureAccessibility()
        if (tickCount % 15 == 2L) keepServicesStopped()
        if (tickCount % 10 == 4L && !recents.isOpen) recents.maybeCaptureHome(interactive)
        if (tickCount % 5 == 3L) checkModuleFlags()
    }

    /**
     * system-tweaks.sh stops services for hardware the Thor doesn't have. A zygote restart
     * (soft reboot) starts some of them again through its onrestart list; stop them again.
     */
    private fun keepServicesStopped() {
        for (svc in STOPPED_SERVICES) {
            if (Sys.prop("init.svc.$svc") == "running") {
                Sys.sh("stop $svc")
                logi("stopped $svc again")
            }
        }
    }

    /** Disabling / removing the module in the Magisk app: exit, so service.sh restores everything. */
    private fun checkModuleFlags() {
        if (File("$MODULE_DIR/disable").exists() || File("$MODULE_DIR/remove").exists()) {
            logi("module disabled or removed, exiting")
            game.setEnabled(false)
            gamepad.shutdown()
            kotlin.system.exitProcess(0)
        }
    }

    private fun reapply() {
        try {
            leds.invalidate()
            router.apply(cfg.controller)
        } catch (e: Throwable) {
            logw("reapply failed", e)
        }
    }

    /** The overlay lives in an accessibility service so nothing can draw above or hide it. */
    private fun ensureAccessibility() {
        if (!File("/data/data/${Proto.PACKAGE}").exists()) return
        val cur = Sys.sh("cmd settings get secure enabled_accessibility_services") ?: return
        if (cur.split(':').contains(Proto.A11Y_SERVICE)) return
        val next = if (cur.isBlank() || cur == "null") Proto.A11Y_SERVICE else "$cur:${Proto.A11Y_SERVICE}"
        Sys.sh("cmd settings put secure enabled_accessibility_services '$next'")
        Sys.sh("cmd settings put secure accessibility_enabled 1")
        logi("re-enabled accessibility service")
    }

    // ---- buttons ----------------------------------------------------------------------------

    override fun onAynTap() {
        // Gamepad mode with the screens dark (or asleep after all): AYN brings them back (the
        // controller stays on the other device).
        if (gamepad.lightUp()) return
        if (gamepad.state.on && !SystemServices.isInteractive()) {
            SystemServices.injectKey(KeyEvent.KEYCODE_WAKEUP, displays.topId)
            return
        }
        if (recents.isOpen) {
            recents.back()
            return
        }
        if (displays.bottomForcedOff) {
            setBottomPower(true)
            return
        }
        if (server.hasClients) {
            server.broadcast("""{"ev":"button","type":"short"}""")
        } else {
            // App not connected (still starting, or crashed): wake it through an explicit broadcast.
            Sys.sh("am broadcast -a ${Proto.ACTION_TOGGLE_MENU} -n ${Proto.PACKAGE}/.app.CommandReceiver --receiver-foreground")
        }
    }

    override fun onCombo(combo: InputWatcher.Combo) {
        logi("combo AYN+$combo")
        when (combo) {
            InputWatcher.Combo.DOWN -> setController(Proto.TARGET_BOTTOM, announce = true)
            InputWatcher.Combo.UP -> setController(Proto.TARGET_TOP, announce = true)
            InputWatcher.Combo.LEFT, InputWatcher.Combo.RIGHT -> setController(Proto.TARGET_FREE, announce = true)
            InputWatcher.Combo.B -> moveApp(toBottom = true)
            InputWatcher.Combo.X -> moveApp(toBottom = false)
            InputWatcher.Combo.Y -> setGame(!game.enabled, announce = true)
            InputWatcher.Combo.L1 -> stepBrightness("bottom", -1)
            InputWatcher.Combo.R1 -> stepBrightness("bottom", 1)
            InputWatcher.Combo.L2 -> stepBrightness("top", -1)
            InputWatcher.Combo.R2 -> stepBrightness("top", 1)
        }
    }

    /** The screen Home acts on: where the controller is routed, or the focused one when it follows focus. */
    private fun homeDisplay(): Int {
        val d = when (router.target) {
            Proto.TARGET_TOP -> displays.topId
            Proto.TARGET_BOTTOM -> displays.bottomId
            else -> SystemServices.focusedDisplayId()
        }
        return if (d < 0 || (d == displays.bottomId && !displays.bottomOn)) displays.topId else d
    }

    override fun onShoulder() {
        if (keyboardVisible) server.broadcast("""{"ev":"kb_key","key":"shift"}""")
    }

    override fun onHomeDown() {
        recents.homeDown(homeDisplay())
    }

    override fun onHomeSingle() {
        val d = homeDisplay()
        // Inside an app: Thor Control animates to the home screen and sends Home itself.
        if (recents.homeShort(d)) return
        SystemServices.injectKey(KeyEvent.KEYCODE_HOME, d)
        nudgeGame()
    }

    /** Leaving or returning to the game: let game mode react now instead of on its next tick. */
    private fun nudgeGame() {
        if (!game.enabled) return
        sched.schedule({
            try {
                game.tick(SystemServices.isInteractive())
            } catch (e: Throwable) {
                logw("game mode tick failed", e)
            }
        }, 500, TimeUnit.MILLISECONDS)
    }

    override fun onHomeLong() {
        server.broadcast("""{"ev":"haptic","effect":"long_press"}""")
        if (!server.hasClients) {
            // App not connected: fall back to the system's switcher (Trebuchet, if enabled).
            SystemServices.injectKey(KeyEvent.KEYCODE_APP_SWITCH, displays.topId)
            return
        }
        recents.homeLong(homeDisplay())
    }

    /** One 10 % step on the same perceptual curve as the menu sliders (snapped to whole 5 %). */
    private fun stepBrightness(which: String, dir: Int) {
        val pos = BrightnessCurve.toPosition(displays.getBrightness(which))
        val next = (((pos * 20f).roundToInt() + dir * 2).coerceIn(0, 20)) / 20f
        synchronized(this) { displays.setBrightness(which, BrightnessCurve.toLinear(next)) }
        broadcastState()
        // The pill shows on the screen that changed (on the top one if the bottom one is off).
        val d = if (which == "bottom" && displays.bottomOn && displays.bottomId >= 0) displays.bottomId else displays.topId
        hud(JSONObject().put("kind", "brightness").put("which", which).put("pct", (next * 100f).roundToInt()), d)
    }

    private fun setGame(on: Boolean, announce: Boolean) {
        game.setEnabled(on)
        broadcastState()
        if (announce) hud(JSONObject().put("kind", "game").put("state", if (on) "on" else "off"), displays.topId)
        // Picking the game and closing other apps takes a moment; keep it off the caller's thread.
        if (on) {
            sched.execute {
                try {
                    game.tick(SystemServices.isInteractive())
                } catch (e: Throwable) {
                    logw("game mode tick failed", e)
                }
            }
        }
    }

    private fun setController(target: String, announce: Boolean) {
        synchronized(this) {
            cfg.controller = target
            router.apply(target)
            saveSoon()
        }
        broadcastState()
        if (announce) {
            val display = if (target == Proto.TARGET_BOTTOM) displays.bottomId else displays.topId
            hud(JSONObject().put("kind", "controller").put("target", target), display)
        }
    }

    private fun moveApp(toBottom: Boolean) {
        val from = if (toBottom) displays.topId else displays.bottomId
        val to = if (toBottom) displays.bottomId else displays.topId
        val toName = if (toBottom) Proto.TARGET_BOTTOM else Proto.TARGET_TOP
        if (from < 0 || to < 0) return
        val task = try {
            SystemServices.topAppTask(from)
        } catch (e: Throwable) {
            logw("topAppTask failed", e)
            null
        }
        if (task == null) {
            hud(JSONObject().put("kind", "nothing").put("from", if (toBottom) Proto.TARGET_TOP else Proto.TARGET_BOTTOM), from)
            return
        }
        if (toBottom && displays.bottomForcedOff) setBottomPower(true)
        try {
            SystemServices.moveTaskToDisplay(task.taskId, to)
            // With "follow focus" routing, input should follow the app to its new screen.
            if (router.target == Proto.TARGET_FREE) SystemServices.focusTopTask(to)
            logi("moved task ${task.taskId} (${task.pkg}) to display $to")
            hud(JSONObject().put("kind", "moved").put("pkg", task.pkg ?: "").put("to", toName), to)
        } catch (e: Throwable) {
            logw("move task failed", e)
        }
    }

    private fun hud(o: JSONObject, displayId: Int) {
        recents.noteHud()
        server.broadcast(o.put("ev", "hud").put("display", displayId).toString())
    }

    private fun setBottomPower(on: Boolean) {
        synchronized(this) { displays.setBottomPower(on) }
        broadcastState()
    }

    // ---- protocol ---------------------------------------------------------------------------

    private fun isAllowedUid(uid: Int): Boolean {
        if (uid == 0 || uid == 1000 || uid == 2000) return true
        if (uid == appUid) return true
        appUid = try {
            Os.stat("/data/data/${Proto.PACKAGE}").st_uid
        } catch (_: Exception) {
            -1
        }
        return uid == appUid
    }

    private fun state(): DaemonState = DaemonState(
        fanProfile = cfg.fanProfile,
        profiles = cfg.allProfiles(),
        brightnessTop = displays.getBrightness("top"),
        brightnessBottom = displays.getBrightness("bottom"),
        controller = cfg.controller,
        leds = cfg.leds,
        bottomOn = displays.bottomOn,
        topDisplayId = displays.topId,
        bottomDisplayId = displays.bottomId,
        fanKernelMode = fan.kernelMode,
        fanOverride = game.fanOverride.orEmpty(),
        game = game.state(),
        gamepad = gamepad.state,
    )

    private fun broadcastState() {
        server.broadcast(state().toJson().toString())
    }

    private fun saveSoon() {
        saveTask?.cancel(false)
        saveTask = sched.schedule({ cfg.save() }, 400, TimeUnit.MILLISECONDS)
    }

    private fun handle(client: SocketServer.Client, cmd: JSONObject) {
        var changed = true
        when (cmd.optString("cmd")) {
            "controller" -> {
                val t = cmd.getString("target")
                if (t in listOf(Proto.TARGET_FREE, Proto.TARGET_TOP, Proto.TARGET_BOTTOM)) setController(t, announce = false)
                return
            }
            "move_app" -> {
                moveApp(cmd.optString("to") == Proto.TARGET_BOTTOM)
                return
            }
            "game_mode" -> {
                setGame(cmd.getBoolean("on"), announce = false)
                return
            }
            "kb_visible" -> {
                keyboardVisible = cmd.optBoolean("on")
                return
            }
            "gamepad" -> {
                gamepad.setOn(cmd.optBoolean("on"))
                return
            }
            "gamepad_transport" -> {
                gamepad.setTransport(cmd.optString("transport"))
                synchronized(this) {
                    cfg.gamepadTransport = gamepad.state.transport
                    saveSoon()
                }
                return
            }
            "gamepad_screens_off" -> {
                gamepad.setScreensOff(cmd.optBoolean("on"))
                synchronized(this) {
                    cfg.gamepadScreensOff = gamepad.state.screensOff
                    saveSoon()
                }
                return
            }
            "gamepad_bt" -> {
                gamepad.onBluetooth(cmd.optBoolean("connected"), cmd.optString("host"))
                return
            }
            "recents_open", "recents_home", "recents_remove", "recents_clear", "recents_close", "home_go", "overlay_done" -> {
                recents.handle(cmd)
                return
            }
        }
        synchronized(this) {
            when (cmd.optString("cmd")) {
                "hello", "get_state" -> {
                    client.send(state().toJson().toString())
                    changed = false
                }
                "stats" -> {
                    client.wantsStats = cmd.optBoolean("on")
                    if (client.wantsStats) client.send(stats.collect().toJson().toString())
                    changed = false
                }
                "fan_profile" -> {
                    val id = cmd.getString("id")
                    if (cfg.profile(id) != null) {
                        cfg.fanProfile = id
                        saveSoon()
                        fanTick()
                    }
                }
                "fan_save" -> {
                    val p = sanitize(FanProfile.fromJson(cmd.getJSONObject("profile")))
                    if (p != null && !p.isBuiltin) {
                        val i = cfg.customProfiles.indexOfFirst { it.id == p.id }
                        if (i >= 0) cfg.customProfiles[i] = p else cfg.customProfiles += p
                        if (cmd.optBoolean("activate")) cfg.fanProfile = p.id
                        saveSoon()
                        fanTick()
                    }
                }
                "fan_delete" -> {
                    val id = cmd.getString("id")
                    if (cfg.customProfiles.removeAll { it.id == id }) {
                        if (cfg.fanProfile == id) cfg.fanProfile = "smart"
                        saveSoon()
                        fanTick()
                    }
                }
                "brightness" -> {
                    displays.setBrightness(cmd.getString("display"), cmd.getDouble("value").toFloat())
                    // Brightness drags are high-frequency; the slider owns its value while dragging.
                    changed = cmd.optBoolean("final", false)
                }
                "leds" -> {
                    cfg.leds = LedState.fromJson(cmd.getJSONObject("state"))
                    leds.apply(cfg.leds)
                    saveSoon()
                }
                "bottom_power" -> displays.setBottomPower(cmd.getBoolean("on"))
                "game_options" -> {
                    cfg.gameOptions = GameOptions.fromJson(cmd.getJSONObject("options"))
                    game.setOptions(cfg.gameOptions)
                    saveSoon()
                    fanTick()
                }
                "keep_accessibility" -> {
                    cfg.keepAccessibility = cmd.getBoolean("on")
                    saveSoon()
                }
                else -> changed = false
            }
        }
        if (changed) broadcastState()
    }

    companion object {
        const val MODULE_DIR = "/data/adb/modules/thorctl"
        val STOPPED_SERVICES = listOf("cameraserver", "wifidisplayhalservice")
    }

    private fun sanitize(p: FanProfile): FanProfile? {
        if (p.id.isBlank() || p.points.size < 2 || p.points.size > 8) return null
        val pts = p.points
            .map { FanPoint(it.temp.coerceIn(20f, 100f), it.pct.coerceIn(0f, 100f)) }
            .sortedBy { it.temp }
        return p.copy(name = p.name.take(24).ifBlank { "Custom" }, points = pts)
    }
}
