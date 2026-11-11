package com.thorctl.daemon

import android.os.SystemClock
import android.util.Base64
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The Home button's screens, replacing Trebuchet: recent apps (hold) and the animated way home
 * (short press). The app draws both; this side does the system work.
 *
 * Spatially, recent apps are a row of cards: oldest on the left, the app you came from next, and
 * the home screen always rightmost. Pressing Home "zooms out" of the app, slides one card right to
 * the home screen and zooms into it. For that the app needs pictures of exactly what is on screen:
 *  - the moment Home goes down, the screen is captured (screencap's JPEG mode, ~140 ms), before
 *    we know whether it becomes a press or a hold;
 *  - the home screen is captured whenever it is in front and nothing of ours covers it, and kept
 *    on disk (one per display), so it is ready when Home is pressed inside an app;
 *  - other apps use the previews the system persists in /data/system_ce/0/snapshots/.
 *
 * Events to the app:   recents {display, origin: app|home, originTask, hero, home, tasks: [{id, pkg,
 *                      display, visible, running, thumb}]}, recents_thumb {id, thumb},
 *                      recents_key {key}, recents_close, home_anim {seq, display, app, home}
 * Commands from it:    recents_open {id}, recents_home, recents_close, recents_remove {id},
 *                      recents_clear {ids}, home_go {seq}, overlay_done
 *
 * Only running tasks are opened from here. Restoring a task that is no longer running counts as
 * a background activity start for this (root, windowless) process and Android blocks it; the app
 * launches those itself (it is a persistent process with a visible window, which is allowed).
 */
internal class Recents(
    private val displays: DisplayController,
    private val input: InputWatcher,
    private val send: (String) -> Unit,
    private val hasClient: () -> Boolean,
    /** Home was brought to front (game mode wants to know straight away). */
    private val onHomeShown: () -> Unit,
) {
    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "recents") }
    private val capExec = Executors.newSingleThreadExecutor { Thread(it, "capture") }

    /** Display the recents screen is open on, or -1. */
    @Volatile
    var openOn = -1
        private set

    val isOpen: Boolean get() = openOn >= 0

    private var tasks: List<SystemServices.RecentTask> = emptyList()
    private var idle: ScheduledFuture<*>? = null

    /** One of our full-screen animations is (or may still be) on screen: don't capture. */
    @Volatile
    private var busyUntil = 0L

    @Volatile
    private var lastHud = 0L

    // ---- Home button ------------------------------------------------------------------------

    private class Press(val display: Int, val origin: SystemServices.TaskDesc?, val file: File, val shot: Future<Boolean>)

    @Volatile
    private var press: Press? = null
    private var seq = 0
    private var pendingHome: Pair<Int, Int>? = null // seq to display

    /** Home went down: capture the screen now, in case this becomes the zoom-out of a press or hold. */
    fun homeDown(display: Int) {
        press = null
        if (display < 0 || isOpen || busy()) return
        val origin = try {
            SystemServices.topAppTask(display)
        } catch (_: Throwable) {
            null
        }
        // On the home screen the capture doubles as a fresh picture of it.
        val file = if (origin == null) homeFile(display) else File(DIR, "screen.jpg")
        press = Press(display, origin, file, capExec.submit<Boolean> { capture(display, file) })
    }

    /**
     * Short press. Returns false when the caller should just send Home: already home, no app
     * connected, or no picture of the home screen yet (it is taken the next time home shows).
     */
    fun homeShort(display: Int): Boolean {
        val p = press ?: return false
        press = null
        if (p.display != display || p.origin == null || !hasClient()) return false
        val home = homeFile(display)
        if (!home.exists()) {
            captureHomeLater(display)
            return false
        }
        run {
            val ok = try {
                p.shot.get(800, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                false
            }
            val s = ++seq
            pendingHome = s to display
            if (!ok) {
                goHome(s)
                return@run
            }
            busy(4000)
            send(
                JSONObject().put("ev", "home_anim").put("seq", s).put("display", display)
                    .put("app", b64(p.file)).put("home", b64(home)).toString(),
            )
            // The app starts the switch once its overlay covers the screen; never wait long for it.
            exec.schedule({ goHome(s) }, 800, TimeUnit.MILLISECONDS)
        }
        return true
    }

    /** Hold: open recent apps (or close them if already open). */
    fun homeLong(display: Int) = run {
        if (isOpen) {
            close(notifyApp = true)
            return@run
        }
        val p = press?.takeIf { it.display == display }
        press = null
        val origin = p?.origin ?: try {
            SystemServices.topAppTask(display)
        } catch (_: Throwable) {
            null
        }
        val file = p?.file ?: (if (origin == null) homeFile(display) else File(DIR, "screen.jpg"))
        val ok = try {
            p?.shot?.get(800, TimeUnit.MILLISECONDS) ?: capture(display, file)
        } catch (_: Exception) {
            false
        }
        open(display, origin, if (ok) file else null)
    }

    private fun goHome(s: Int) {
        val (ps, d) = pendingHome ?: return
        if (ps != s) return
        pendingHome = null
        SystemServices.injectKey(KeyEvent.KEYCODE_HOME, d)
        onHomeShown()
        captureHomeLater(d)
    }

    // ---- Home screen pictures ---------------------------------------------------------------

    fun noteHud() {
        lastHud = SystemClock.elapsedRealtime()
    }

    private fun busy(): Boolean = isOpen || SystemClock.elapsedRealtime() < busyUntil

    private fun busy(ms: Long) {
        busyUntil = SystemClock.elapsedRealtime() + ms
    }

    private fun homeFile(display: Int) = File(DIR, "home-${physicalId(display)}.jpg")

    private fun physicalId(display: Int): String =
        (if (display == displays.bottomId) displays.bottomUniqueId else displays.topUniqueId).substringAfter("local:")

    /** Called every couple of seconds: keeps the top screen's home picture fresh while it shows. */
    fun maybeCaptureHome(interactive: Boolean) {
        val d = displays.topId
        if (!interactive || d < 0) return
        val f = homeFile(d)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < HOME_MAX_AGE_MS) return
        captureHomeIfShowing(d)
    }

    private fun captureHomeLater(display: Int) {
        exec.schedule({ captureHomeIfShowing(display) }, 1500, TimeUnit.MILLISECONDS)
    }

    private fun captureHomeIfShowing(display: Int) {
        capExec.execute {
            try {
                if (busy() || SystemClock.elapsedRealtime() - lastHud < 3000) return@execute
                if (SystemServices.topAppTask(display) != null) return@execute
                if (capture(display, homeFile(display))) logi("recents: home screen of display $display captured")
            } catch (e: Throwable) {
                logw("home capture failed", e)
            }
        }
    }

    private fun capture(display: Int, file: File): Boolean {
        val phys = physicalId(display)
        if (phys.isEmpty()) return false
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.delete()
        Sys.sh("screencap -d $phys -j ${tmp.path}", 3000)
        if (!tmp.exists() || tmp.length() < 1000) {
            tmp.delete()
            return false
        }
        return tmp.renameTo(file)
    }

    private fun b64(f: File?): String = try {
        if (f == null || !f.isFile || f.length() > MAX_IMAGE_BYTES) "" else Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    } catch (_: Exception) {
        ""
    }

    // ---- Recents session --------------------------------------------------------------------

    fun close() = run { close(notifyApp = true) }

    /** AYN while open: same as B (back to where recents was opened from). */
    fun back() = run { if (isOpen) key("b") }

    fun handle(cmd: JSONObject) = run {
        when (cmd.optString("cmd")) {
            "home_go" -> {
                goHome(cmd.optInt("seq"))
                return@run
            }
            "overlay_done" -> {
                busyUntil = 0
                return@run
            }
        }
        if (!isOpen) return@run
        val d = openOn
        when (cmd.optString("cmd")) {
            "recents_open" -> {
                val id = cmd.getInt("id")
                val t = tasks.firstOrNull { it.taskId == id }
                close(notifyApp = false)
                openTask(id, t, d)
                onHomeShown() // something else is in front now: let game mode look
            }
            "recents_home" -> {
                close(notifyApp = false)
                SystemServices.injectKey(KeyEvent.KEYCODE_HOME, d)
                onHomeShown()
                captureHomeLater(d)
            }
            "recents_remove" -> {
                val id = cmd.getInt("id")
                SystemServices.removeTask(id)
                tasks = tasks.filter { it.taskId != id }
                logi("recents: closed task $id")
            }
            "recents_clear" -> {
                val ids = cmd.optJSONArray("ids")?.let { a -> List(a.length()) { a.getInt(it) } } ?: tasks.map { it.taskId }
                ids.forEach { SystemServices.removeTask(it) }
                tasks = emptyList()
                logi("recents: closed all (${ids.size})")
            }
            "recents_close" -> close(notifyApp = false)
        }
    }

    private fun run(block: () -> Unit) {
        exec.execute {
            try {
                block()
            } catch (e: Throwable) {
                logw("recents failed", e)
            }
        }
    }

    private fun open(displayId: Int, origin: SystemServices.TaskDesc?, hero: File?) {
        val list = SystemServices.recentTasks(MAX_TASKS).toMutableList()
        // The app you came from is "the most recent" one, right next to the home screen.
        if (origin != null) {
            val i = list.indexOfFirst { it.taskId == origin.taskId }
            if (i > 0) list.add(0, list.removeAt(i))
        }
        // Apps visible elsewhere (the other screen) get a fresh preview; written within ~150 ms.
        val requested = System.currentTimeMillis()
        val others = list.filter { it.visible && it.taskId != origin?.taskId }
        others.forEach {
            try {
                SystemServices.takeSnapshot(it.taskId)
            } catch (e: Throwable) {
                logw("snapshot of task ${it.taskId} failed", e)
            }
        }
        tasks = list
        openOn = displayId
        val arr = JSONArray()
        for (t in list) {
            val thumb = if (t.taskId == origin?.taskId && hero != null) "" else thumb(t.taskId)
            arr.put(
                JSONObject().put("id", t.taskId).put("pkg", t.pkg).put("display", t.displayId)
                    .put("visible", t.visible).put("running", t.running).put("thumb", thumb),
            )
        }
        val homeImg = homeFile(displayId)
        send(
            JSONObject().put("ev", "recents").put("display", displayId)
                .put("origin", if (origin == null) "home" else "app")
                .put("originTask", origin?.taskId ?: -1)
                .put("hero", if (origin != null) b64(hero) else "")
                .put("home", b64(homeImg.takeIf { it.exists() }))
                .put("tasks", arr).toString(),
        )

        ignoreHomeUp = input.isDown(InputWatcher.KEY_HOME)
        homeDown = false
        dirs.clear()
        held = null
        val grabbed = input.grabController(::onPad)
        logi("recents: open on display $displayId from ${origin?.pkg ?: "home"}, ${list.size} apps (controller ${if (grabbed) "grabbed" else "not grabbed"})")
        bumpIdle()
        if (others.isNotEmpty()) refreshThumbs(others.map { it.taskId }, requested, attempt = 0)
    }

    /** Sends the fresh previews of apps on the other screen once the system has written them. */
    private fun refreshThumbs(ids: List<Int>, since: Long, attempt: Int) {
        exec.schedule({
            if (!isOpen) return@schedule
            val pending = ids.filter { id ->
                val f = SystemServices.snapshotFile(id)
                if (f != null && f.lastModified() >= since - 50) {
                    send(JSONObject().put("ev", "recents_thumb").put("id", id).put("thumb", thumb(id)).toString())
                    false
                } else {
                    true
                }
            }
            if (pending.isNotEmpty() && attempt < 3) refreshThumbs(pending, since, attempt + 1)
        }, 200L + attempt * 250L, TimeUnit.MILLISECONDS)
    }

    private fun thumb(taskId: Int): String = b64(SystemServices.snapshotFile(taskId))

    private fun openTask(id: Int, t: SystemServices.RecentTask?, display: Int) {
        try {
            if (t != null && t.displayId >= 0 && t.displayId != display) {
                // Running on the other screen: bring it over to where the user is looking.
                SystemServices.moveTaskToDisplay(id, display)
            } else {
                SystemServices.startFromRecents(id, display)
            }
            logi("recents: opened task $id (${t?.pkg}) on display $display")
        } catch (e: Throwable) {
            logw("recents: open task $id failed", e)
        }
    }

    private fun close(notifyApp: Boolean) {
        if (!isOpen) return
        openOn = -1
        idle?.cancel(false)
        idle = null
        held = null
        repeat?.cancel(false)
        repeat = null
        input.releaseController()
        tasks = emptyList()
        // The app animates into the chosen screen and then removes its overlay.
        busy(2500)
        if (notifyApp) send("""{"ev":"recents_close"}""")
    }

    /** A crashed or stuck app must not keep the controller forever. */
    private fun bumpIdle() {
        idle?.cancel(false)
        idle = exec.schedule({
            logw("recents: idle for ${IDLE_CLOSE_MS / 1000}s, closing")
            close(notifyApp = true)
        }, IDLE_CLOSE_MS, TimeUnit.MILLISECONDS)
    }

    // ---- Controller -------------------------------------------------------------------------

    private var ignoreHomeUp = false
    private var homeDown = false

    /** Which inputs hold left (-1) or right (+1): d-pad hat, stick, d-pad keys, bumpers. */
    private val dirs = HashMap<String, Int>()
    @Volatile
    private var held: Int? = null
    private var repeat: ScheduledFuture<*>? = null
    private var stickX = 0

    /** Raw evdev events from the grabbed controller (on the grab reader thread). */
    private fun onPad(type: Int, code: Int, value: Int) {
        when (type) {
            InputWatcher.EV_KEY -> onKey(code, value)
            InputWatcher.EV_ABS -> onAxis(code, value)
        }
    }

    private fun onKey(code: Int, value: Int) {
        when (code) {
            InputWatcher.KEY_HOME -> {
                // The hold that opened this screen ends here; a new press goes home.
                if (value == 0 && ignoreHomeUp) {
                    ignoreHomeUp = false
                    return
                }
                if (value == 1) homeDown = true
                if (value == 0 && homeDown) {
                    homeDown = false
                    key("home")
                }
                return
            }
            BTN_TL, InputWatcher.BTN_DPAD_LEFT, InputWatcher.KEY_LEFT -> setDir("k$code", if (value != 0) -1 else 0)
            BTN_TR, InputWatcher.BTN_DPAD_RIGHT, InputWatcher.KEY_RIGHT -> setDir("k$code", if (value != 0) 1 else 0)
        }
        if (value != 1) return
        when (code) {
            BTN_SOUTH, BTN_START -> key("a")
            InputWatcher.BTN_EAST, KEY_BACK -> key("b")
            InputWatcher.BTN_NORTH -> key("x")
            InputWatcher.BTN_WEST -> key("y")
        }
    }

    private fun onAxis(code: Int, value: Int) {
        when (code) {
            InputWatcher.ABS_HAT0X -> setDir("hat", value.coerceIn(-1, 1))
            ABS_X -> {
                stickX = when {
                    value <= -STICK_ON -> -1
                    value >= STICK_ON -> 1
                    kotlin.math.abs(value) < STICK_OFF -> 0
                    else -> stickX
                }
                setDir("stick", stickX)
            }
        }
    }

    /** Held left/right moves one card, then keeps moving after a short pause (like a key repeat). */
    private fun setDir(source: String, d: Int) {
        if (d == 0) dirs.remove(source) else dirs[source] = d
        val now = dirs.values.firstOrNull()
        if (now == held) return
        held = now
        repeat?.cancel(false)
        repeat = null
        if (now == null) return
        val k = if (now < 0) "left" else "right"
        key(k)
        repeat = exec.scheduleWithFixedDelay({ if (held == now) key(k) }, REPEAT_DELAY_MS, REPEAT_EVERY_MS, TimeUnit.MILLISECONDS)
    }

    private fun key(k: String) {
        bumpIdle()
        send(JSONObject().put("ev", "recents_key").put("key", k).toString())
    }

    companion object {
        val DIR = File("/data/adb/thorctl")
        const val MAX_TASKS = 16
        const val MAX_IMAGE_BYTES = 1_200_000L
        const val HOME_MAX_AGE_MS = 60_000L
        const val IDLE_CLOSE_MS = 120_000L
        const val REPEAT_DELAY_MS = 380L
        const val REPEAT_EVERY_MS = 170L
        const val STICK_ON = 20000
        const val STICK_OFF = 8000
        const val BTN_SOUTH = 304 // "A"
        const val BTN_TL = 310
        const val BTN_TR = 311
        const val BTN_START = 315
        const val KEY_BACK = 158
        const val ABS_X = 0
    }
}
