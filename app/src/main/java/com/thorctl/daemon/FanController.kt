package com.thorctl.daemon

import com.thorctl.shared.FanProfile
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Drives the pwm-fan (hwmon "pwmfan", pwm1 0..255).
 *
 * The kernel normally controls this fan through step_wise thermal zones whose trip points are
 * bound to the "pwm-fan" cooling device (cpu-1-9, cpu-1-10, gpuss-0/1, usb-therm). In the Auto
 * profile we leave that alone. For curve profiles we raise only the fan-bound trip points out of
 * reach (CPU/GPU throttling trips in the same zones are untouched) and drive pwm1 ourselves.
 *
 * Original trip values are saved in /dev (tmpfs, reset every boot) so that the supervisor script
 * can restore kernel control if this process ever dies.
 */
internal class FanController {
    private data class Trip(val path: String, val original: Int)

    private val pwmPath: String? = File("/sys/class/hwmon").listFiles()
        ?.firstOrNull { Sys.read("${it.path}/name") == "pwmfan" }
        ?.let { "${it.path}/pwm1" }

    private val coolingDevice: File? = File("/sys/class/thermal").listFiles()
        ?.firstOrNull { it.name.startsWith("cooling_device") && Sys.read("${it.path}/type") == "pwm-fan" }

    private val trips: List<Trip>
    private val controlSensors: List<String>
    private val usbSensors: List<String>

    @Volatile
    var kernelMode = true
        private set

    @Volatile
    var currentPwm = 0
        private set

    @Volatile
    var controlTemp = 0f
        private set

    private var filtered = 0f
    private var screenOffHot = false

    init {
        val cdev = coolingDevice?.canonicalPath
        val found = ArrayList<Pair<String, Int>>()
        val sensors = LinkedHashSet<String>()
        val usb = LinkedHashSet<String>()
        if (cdev != null) {
            File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") }?.forEach { zone ->
                val type = Sys.read("${zone.path}/type") ?: return@forEach
                zone.listFiles()?.filter { it.name.matches(Regex("cdev\\d+")) }?.forEach { link ->
                    if (link.canonicalPath == cdev) {
                        val trip = Sys.readInt("${link.path}_trip_point") ?: return@forEach
                        found += "${zone.path}/trip_point_${trip}_temp" to (Sys.readInt("${zone.path}/trip_point_${trip}_temp") ?: 0)
                        if (type.startsWith("usb")) usb += "${zone.path}/temp" else sensors += "${zone.path}/temp"
                    }
                }
            }
        }
        controlSensors = sensors.toList()
        usbSensors = usb.toList()
        trips = loadOrSaveOriginals(found)
        logi("fan: pwm=$pwmPath cdev=${coolingDevice?.name} trips=${trips.size} sensors=${controlSensors.size}+${usbSensors.size}")
    }

    /** Original values are captured once per boot; a restarted daemon must not capture our own overrides. */
    private fun loadOrSaveOriginals(current: List<Pair<String, Int>>): List<Trip> {
        val dir = File(STATE_DIR)
        dir.mkdirs()
        val f = File(dir, "trips.orig")
        if (f.exists()) {
            val saved = f.readLines().mapNotNull { line ->
                val parts = line.trim().split(' ')
                if (parts.size == 2) parts[0] to parts[1].toIntOrNull() else null
            }.filter { it.second != null }.associate { it.first to it.second!! }
            if (saved.isNotEmpty()) return current.map { Trip(it.first, saved[it.first] ?: it.second.takeIf { v -> v < DISABLED_TEMP } ?: 60000) }
        }
        val list = current.map { Trip(it.first, if (it.second >= DISABLED_TEMP) 60000 else it.second) }
        f.writeText(list.joinToString("\n") { "${it.path} ${it.original}" } + "\n")
        return list
    }

    val available: Boolean get() = pwmPath != null

    fun setKernelControl(enabled: Boolean) {
        if (trips.isEmpty()) {
            kernelMode = enabled
            return
        }
        for (t in trips) Sys.write(t.path, if (enabled) t.original.toString() else DISABLED_TEMP.toString())
        kernelMode = enabled
        logi("fan: kernel control ${if (enabled) "restored" else "disabled"}")
    }

    private fun readMax(paths: List<String>): Float =
        paths.mapNotNull { Sys.readInt(it) }.maxOrNull()?.let { it / 1000f } ?: 0f

    /**
     * Called once per second. With the screen off (lid closed) the fan stops in every profile:
     * games pause and the clocks drop, so the chip cools on its own. It only keeps running if
     * something still heats the chip or the charging port past [SCREEN_OFF_MAX_TEMP] /
     * [SCREEN_OFF_MAX_USB_TEMP]; then the selected profile (and the 90 °C safety net) apply.
     */
    fun tick(profile: FanProfile, screenOn: Boolean) {
        val pwm = pwmPath ?: return
        val t = readMax(controlSensors)
        controlTemp = t

        // Hysteresis: once heat brought the fan back with the screen off, it runs until well below.
        val usb = readMax(usbSensors)
        screenOffHot = !screenOn && (t >= SCREEN_OFF_MAX_TEMP || usb >= SCREEN_OFF_MAX_USB_TEMP ||
            (screenOffHot && (t >= SCREEN_OFF_MAX_TEMP - 7f || usb >= SCREEN_OFF_MAX_USB_TEMP - 3f)))
        if (!screenOn && !screenOffHot) {
            if (kernelMode) {
                setKernelControl(false)
                releaseKernelTargets()
            }
            if (Sys.readInt(pwm) != 0) Sys.write(pwm, "0")
            lastWritten = 0
            currentPwm = 0
            filtered = t
            return
        }

        if (profile.isAuto) {
            if (!kernelMode) setKernelControl(true)
            currentPwm = Sys.readInt(pwm) ?: 0
            lastWritten = -1
            filtered = t
            return
        }
        if (kernelMode) {
            setKernelControl(false)
            releaseKernelTargets()
        }

        // Per-core sensors spike for a second at a time (app launches, JIT). Rise fairly quickly,
        // fall slowly, so the fan neither bursts on spikes nor hunts around a curve knee.
        filtered = if (filtered == 0f) t else filtered + (t - filtered) * (if (t >= filtered) 0.35f else 0.12f)
        controlTemp = filtered
        var pct = profile.percentAt(filtered)

        // Charging heat: the stock zones spin the fan for a hot USB port, keep that behaviour.
        val usbT = readMax(usbSensors)
        if (usbT >= 45f) pct = max(pct, 40f) else if (usbT >= 43f) pct = max(pct, 20f)

        // Safety net independent of the selected curve.
        if (filtered >= SAFETY_TEMP || t >= SAFETY_TEMP + 8f) pct = 100f

        var desired = (pct * 2.55f).roundToInt().coerceIn(0, 255)
        if (desired in 1 until MIN_SPIN_PWM) desired = MIN_SPIN_PWM
        var target = desired
        if (target < currentPwm) target = max(target, currentPwm - MAX_STEP_DOWN)
        // Never park the fan in its stall band while ramping down: either min spin or off.
        if (target in 1 until MIN_SPIN_PWM) target = if (desired == 0) 0 else MIN_SPIN_PWM

        // If the kernel keeps overwriting our PWM, its governor has latched targets; release them.
        val actual = Sys.readInt(pwm)
        if (lastWritten >= 0 && actual != null && actual != lastWritten) {
            overriddenTicks++
            if (overriddenTicks >= 2 && android.os.SystemClock.elapsedRealtime() - lastRelease > 10_000) {
                logw("fan: kernel keeps overriding pwm ($actual instead of $lastWritten)")
                releaseKernelTargets()
                overriddenTicks = 0
            }
        } else {
            overriddenTicks = 0
        }
        if (Sys.readInt(pwm) != target) Sys.write(pwm, target.toString())
        lastWritten = target
        currentPwm = target
    }

    private val zoneDirs: List<String> by lazy { trips.map { it.path.substringBeforeLast('/') }.distinct() }
    private var lastWritten = -1
    private var overriddenTicks = 0
    private var lastRelease = 0L

    /**
     * The step_wise governor only lowers a fan target when it sees a temperature *drop*. Once
     * the fan trips are moved out of reach those zones stop interrupting, so whatever targets
     * were active at takeover stay latched and get re-applied over our PWM (the fan "sticks" at
     * a level). Zero the fan state, then feed every fan zone one cold emulated reading: every
     * instance then sees a drop below its level and returns to no-target. Real readings resume
     * right after.
     */
    private fun releaseKernelTargets() {
        val pwm = pwmPath ?: return
        val emul = zoneDirs.map { "$it/emul_temp" }.filter { File(it).exists() }
        Sys.write(pwm, "0")
        if (emul.isEmpty()) return
        emul.forEach { Sys.write(it, "20000") }
        try {
            Thread.sleep(300)
        } finally {
            emul.forEach { Sys.write(it, "0") }
        }
        lastRelease = android.os.SystemClock.elapsedRealtime()
        logi("fan: released latched kernel fan targets in ${emul.size} zones")
    }

    /** Last reading of pwm1, used for stats. */
    fun readPwm(): Int = pwmPath?.let { Sys.readInt(it) } ?: 0

    companion object {
        const val STATE_DIR = "/dev/thorctl"
        const val DISABLED_TEMP = 150000
        const val SAFETY_TEMP = 90f
        const val SCREEN_OFF_MAX_TEMP = 62f
        const val SCREEN_OFF_MAX_USB_TEMP = 48f
        const val MIN_SPIN_PWM = 40
        const val MAX_STEP_DOWN = 12
    }
}
