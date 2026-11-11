package com.thorctl.daemon

import android.app.ActivityManager.RunningAppProcessInfo
import android.content.pm.ApplicationInfo
import com.thorctl.shared.GameOptions
import com.thorctl.shared.GameState
import com.thorctl.shared.Proto
import java.io.File

/**
 * Game mode hands the device to one game:
 *
 *  - Memory: when the game is picked, every other app is closed (user apps are force-stopped,
 *    system apps lose their background processes) and memory is compacted. While it runs,
 *    cached background processes are cleared every ~10 s so nothing piles up again.
 *  - CPU: every cluster held at its top frequency. Core parking (core_ctl) is left alone: the
 *    vendor perf HAL saves and rewrites it on its own schedule, so values written there leak.
 *  - GPU: held at its top power level.
 *  - Fan: optionally switched to a stronger profile while boosting.
 *  - Bottom screen: optionally turned off while the game is in front on the top screen, so the
 *    launcher showing there stops drawing and releases its graphics memory (~120 MB for Cocoon).
 *    Skipped when the game itself shows something on the bottom screen; turning it back on (AYN
 *    tap) keeps it on for the rest of that game.
 *
 * Thermal mitigation still caps CPU/GPU on top of the floors written here (the kernel resolves
 * min > max in favour of max), so this can't cook the chip.
 *
 * "The game" is the user app in front when game mode turns on, or the next one opened after that.
 * It is protected (never closed, even while you switch away to look something up) until its
 * process ends, then game mode waits for the next game. Clocks drop back while the screen is off.
 *
 * Every sysfs value written is first saved to /dev/thorctl/game.orig; restore-kernel.sh puts
 * them back if the daemon dies.
 */
internal class GameMode(
    private val displays: DisplayController,
    private val onChanged: () -> Unit,
    private val onStarted: (pkg: String, freedMb: Int) -> Unit,
    private val onBottomOff: () -> Unit,
) {
    private data class Knob(val path: String, val boost: String)

    @Volatile
    var options = GameOptions()
        private set

    @Volatile
    var enabled = false
        private set

    @Volatile
    var game: String? = null
        private set

    @Volatile
    var boosted = false
        private set

    @Volatile
    var freedMb = 0
        private set

    private var ticks = 0
    private var interactive = true
    private var applied: List<Knob> = emptyList()

    /** Bottom screen turned off by game mode (not by the user). */
    private var bottomOffByUs = false

    /** The user turned the bottom screen back on, or the game uses it: leave it on for this game. */
    private var keepBottomOn = false
    private var frontTicks = 0

    private val onFlag = File(FanController.STATE_DIR, "game.on")
    private val origFile = File(FanController.STATE_DIR, "game.orig")

    /** Fan profile to run instead of the selected one, while boosting. */
    val fanOverride: String? get() = if (boosted && options.fan.isNotEmpty()) options.fan else null

    fun state() = GameState(enabled, game.orEmpty(), boosted, freedMb, options)

    /** Daemon start: keep a game mode that was on earlier this boot, otherwise undo leftovers. */
    @Synchronized
    fun init(opts: GameOptions) {
        options = opts
        enabled = onFlag.exists()
        if (!enabled) restoreSaved()
        logi("game mode: cpu knobs=${cpuKnobs.size} gpu knobs=${gpuKnobs.size} enabled=$enabled")
    }

    /** Turning on only arms it; the next [tick] picks the game (it may block while closing apps). */
    @Synchronized
    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        if (on) {
            onFlag.parentFile?.mkdirs()
            onFlag.writeText("1")
        } else {
            onFlag.delete()
            release()
            bottomOn()
            game = null
            freedMb = 0
            keepBottomOn = false
        }
        logi("game mode ${if (on) "on" else "off"}")
    }

    @Synchronized
    fun setOptions(o: GameOptions) {
        val old = options
        options = o
        if (boosted && (old.cpu != o.cpu || old.gpu != o.gpu)) {
            release()
            apply()
        }
        if (!o.bottomOff) bottomOn()
    }

    /** Called every couple of seconds, and right after game mode is turned on. */
    @Synchronized
    fun tick(interactive: Boolean) {
        this.interactive = interactive
        if (!enabled) return
        val before = state()
        val running = try {
            SystemServices.runningApps()
        } catch (e: Throwable) {
            logw("game mode: listing processes failed", e)
            return
        }

        val g = game
        if (g != null && running.none { g in it.pkgs }) {
            logi("game mode: $g closed, waiting for the next game")
            game = null
            freedMb = 0
            keepBottomOn = false
        }
        if (game == null) {
            pickGame()?.let { pkg ->
                game = pkg
                logi("game mode: game is $pkg")
                freedMb = if (options.closeApps) closeOthers(pkg, running) else 0
                onStarted(pkg, freedMb)
            }
        } else if (options.closeApps && ++ticks % TRIM_EVERY_TICKS == 0) {
            trimCached(running)
        }

        val want = game != null && interactive
        if (want && !boosted) apply() else if (!want && boosted) release() else if (boosted) reassert()
        val bottomChanged = updateBottom(running)
        if (state() != before || bottomChanged) onChanged()
    }

    // ---- Bottom screen --------------------------------------------------------------------

    /** Returns true when the bottom screen's power changed. */
    private fun updateBottom(running: List<SystemServices.AppProcess>): Boolean {
        // Asleep: leave everything as is (waking re-applies a forced-off bottom screen).
        if (!interactive) return false
        if (bottomOffByUs && displays.bottomOn) {
            logi("game mode: bottom screen turned back on by the user, leaving it on for this game")
            bottomOffByUs = false
            keepBottomOn = true
        }
        val g = game
        val inFront = g != null && options.bottomOff && !keepBottomOn && displays.bottomId >= 0 &&
            (try { SystemServices.topAppTask(displays.topId)?.pkg } catch (_: Throwable) { null }) == g
        if (!inFront) {
            frontTicks = 0
            return bottomOn()
        }
        // Wait a couple of seconds in front, so quick switches don't flash the bottom screen.
        if (bottomOffByUs || !displays.bottomOn || ++frontTicks < 2) return false
        if (gameOnBottom(g!!, running)) {
            logi("game mode: $g uses the bottom screen, leaving it on")
            keepBottomOn = true
            return false
        }
        if (!displays.setBottomPower(false)) return false
        bottomOffByUs = true
        onBottomOff()
        return true
    }

    private fun bottomOn(): Boolean {
        if (!bottomOffByUs) return false
        bottomOffByUs = false
        return displays.setBottomPower(true)
    }

    /**
     * Whether the game shows anything on the bottom screen: an activity there, or a window such
     * as a Presentation (dual-screen emulators). Windows aren't tasks, so check SurfaceFlinger's
     * layer tree for layers owned by the game's uid under the bottom display. Only runs when the
     * bottom screen is about to be turned off.
     */
    private fun gameOnBottom(g: String, running: List<SystemServices.AppProcess>): Boolean {
        val b = displays.bottomId
        try {
            if (g in SystemServices.visiblePackages(b)) return true
        } catch (_: Throwable) {
        }
        val uids = running.filter { g in it.pkgs }.map { it.uid }.toSet()
        if (uids.isEmpty()) return false
        val dump = Sys.sh("dumpsys SurfaceFlinger", 4000) ?: return false
        var inBottom = false
        for (line in dump.lineSequence()) {
            val d = SF_DISPLAY.find(line)
            when {
                d != null -> inBottom = d.groupValues[1].toInt() == b
                SF_ROOT_CHILD.containsMatchIn(line) || !line.startsWith(" ") -> inBottom = false
                inBottom -> SF_UID.find(line)?.let { if (it.groupValues[1].toInt() in uids) return true }
            }
        }
        return false
    }

    // ---- Picking the game ------------------------------------------------------------------

    /** The user-installed app in front on the top screen, if any. */
    private fun pickGame(): String? {
        val d = displays.topId
        if (d < 0) return null
        val pkg = try {
            SystemServices.topAppTask(d)?.pkg
        } catch (_: Throwable) {
            null
        } ?: return null
        if (pkg == Proto.PACKAGE || pkg == SystemServices.homePackage()) return null
        val flags = SystemServices.appFlags(pkg) ?: return null
        // Settings, the browser, ... are not games; wait for a user-installed app.
        return if (flags and ApplicationInfo.FLAG_SYSTEM != 0) null else pkg
    }

    // ---- Memory ---------------------------------------------------------------------------

    /** Closes everything but the game and what keeps the device usable. Returns MB freed. */
    private fun closeOthers(game: String, running: List<SystemServices.AppProcess>): Int {
        val before = memAvailableMb()
        val keep = keepSet(game)
        val home = SystemServices.homePackage()
        var stopped = 0
        var trimmed = 0
        val pkgs = running.filter { it.uid >= FIRST_APP_UID }.flatMap { it.pkgs }.toSet() - keep
        for (pkg in pkgs) {
            try {
                val flags = SystemServices.appFlags(pkg) ?: continue
                if (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_PERSISTENT) != 0 || pkg == home) {
                    // System apps: only their background processes, never the app itself.
                    SystemServices.killBackground(pkg)
                    trimmed++
                } else {
                    SystemServices.forceStop(pkg)
                    stopped++
                }
            } catch (e: Throwable) {
                logw("game mode: closing $pkg failed", e)
            }
        }
        Sys.write("/proc/sys/vm/compact_memory", "1")
        Thread.sleep(1200) // let the processes die and the kernel settle before measuring
        val freed = (memAvailableMb() - before).coerceAtLeast(0)
        logi("game mode: stopped $stopped apps, trimmed $trimmed system apps, ${freed} MB freed")
        return freed
    }

    /** Clears background processes that came back (or were opened and left) while playing. */
    private fun trimCached(running: List<SystemServices.AppProcess>) {
        val g = game ?: return
        val pkgs = running
            .filter { it.uid >= FIRST_APP_UID && it.importance >= RunningAppProcessInfo.IMPORTANCE_CACHED && g !in it.pkgs }
            .flatMap { it.pkgs }
            .toSet() - ALWAYS_KEEP - Proto.PACKAGE - g
        if (pkgs.isEmpty()) return
        for (p in pkgs) {
            try {
                SystemServices.killBackground(p)
            } catch (_: Throwable) {
            }
        }
        logi("game mode: cleared ${pkgs.size} background apps (${pkgs.joinToString(limit = 6)})")
    }

    private fun keepSet(game: String): Set<String> {
        val keep = HashSet(ALWAYS_KEEP)
        keep += game
        keep += Proto.PACKAGE
        for (d in intArrayOf(displays.topId, displays.bottomId)) {
            if (d < 0) continue
            try {
                keep += SystemServices.visiblePackages(d) // e.g. a guide open on the other screen
            } catch (_: Throwable) {
            }
        }
        Sys.sh("cmd settings get secure default_input_method")?.let { keep += it.substringBefore('/') }
        Sys.sh("cmd settings get secure enabled_accessibility_services")?.split(':')?.forEach { keep += it.substringBefore('/') }
        keep.removeAll { it.isBlank() || it == "null" }
        return keep
    }

    private fun memAvailableMb(): Int =
        Sys.read("/proc/meminfo")?.lineSequence()?.firstOrNull { it.startsWith("MemAvailable:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.let { (it / 1024).toInt() } ?: 0

    // ---- Clocks ---------------------------------------------------------------------------

    /** Every cpufreq policy's minimum frequency raised to its maximum. */
    private val cpuKnobs: List<Knob> by lazy {
        File("/sys/devices/system/cpu/cpufreq").listFiles()
            ?.filter { it.name.matches(Regex("policy\\d+")) }?.sortedBy { it.name }
            ?.mapNotNull { p -> Sys.read("${p.path}/cpuinfo_max_freq")?.let { Knob("${p.path}/scaling_min_freq", it) } }
            ?: emptyList()
    }

    /** Lowest allowed GPU power level = highest allowed one (level 0 is the top clock). */
    private val gpuKnobs: List<Knob> by lazy {
        val k = "/sys/class/kgsl/kgsl-3d0"
        if (File("$k/min_pwrlevel").exists()) listOf(Knob("$k/min_pwrlevel", Sys.read("$k/max_pwrlevel") ?: "0")) else emptyList()
    }

    /**
     * scaling_min_freq reads back the effective floor, which includes the vendor perf HAL's own
     * boosts (msm_performance). Saving that would make a temporary boost permanent, so restore the
     * floor the vendor boot script sets instead (or the hardware minimum).
     */
    private val vendorMinFreqs: Map<String, String> by lazy {
        val out = HashMap<String, String>()
        val re = Regex("""echo\s+(\d+)\s*>\s*(/sys/devices/system/cpu/cpufreq/policy\d+/scaling_min_freq)""")
        File("/vendor/bin").listFiles()?.filter { it.name.startsWith("init.kernel.post_boot") }?.sortedBy { it.name }
            ?.forEach { f -> Sys.read(f.path)?.let { text -> re.findAll(text).forEach { out[it.groupValues[2]] = it.groupValues[1] } } }
        out
    }

    private fun originalOf(path: String): String? =
        if (path.endsWith("/scaling_min_freq")) {
            vendorMinFreqs[path] ?: Sys.read(path.replace("scaling_min_freq", "cpuinfo_min_freq"))
        } else {
            Sys.read(path)
        }

    private fun apply() {
        val knobs = (if (options.cpu) cpuKnobs else emptyList()) + (if (options.gpu) gpuKnobs else emptyList())
        val originals = LinkedHashMap(loadSaved())
        for (k in knobs) if (k.path !in originals) originalOf(k.path)?.let { originals[k.path] = it }
        saveOriginals(originals)
        for (k in knobs) Sys.write(k.path, k.boost)
        applied = knobs
        boosted = true
        logi("game mode: boost applied (cpu=${options.cpu} gpu=${options.gpu} fan=${options.fan.ifEmpty { "unchanged" }})")
    }

    private fun release() {
        if (!boosted && !origFile.exists()) return
        restoreSaved()
        applied = emptyList()
        boosted = false
        logi("game mode: boost released")
    }

    /** Puts back a GPU floor something else lowered while we were boosting. */
    private fun reassert() {
        for (k in applied) {
            val cur = Sys.read(k.path) ?: continue
            // scaling_min_freq reads back the effective floor, which thermal limits may lower.
            if (cur != k.boost && !k.path.endsWith("/scaling_min_freq")) Sys.write(k.path, k.boost)
        }
    }

    private fun restoreSaved() {
        val saved = loadSaved()
        for ((path, v) in saved) Sys.write(path, v)
        origFile.delete()
    }

    private fun loadSaved(): Map<String, String> {
        if (!origFile.exists()) return emptyMap()
        return origFile.readLines().mapNotNull { line ->
            val parts = line.trim().split(' ')
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) parts[0] to parts[1] else null
        }.toMap()
    }

    private fun saveOriginals(m: Map<String, String>) {
        origFile.parentFile?.mkdirs()
        val tmp = File(origFile.path + ".tmp")
        tmp.writeText(m.entries.joinToString("") { "${it.key} ${it.value}\n" })
        tmp.renameTo(origFile)
    }

    companion object {
        const val FIRST_APP_UID = 10000
        private val SF_DISPLAY = Regex("""^ [├└]─ Display (\d+) """)
        private val SF_ROOT_CHILD = Regex("""^ [├└]─ """)
        private val SF_UID = Regex("""\buid=(\d+)""")
        const val TRIM_EVERY_TICKS = 5

        /** Never closed: core system processes and the storage daemon games read from. */
        val ALWAYS_KEEP = setOf(
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.android.bluetooth",
            "com.android.networkstack",
            "com.android.providers.media.module",
            "com.google.android.providers.media.module",
        )
    }
}
