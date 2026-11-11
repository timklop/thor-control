package com.thorctl.shared

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Data shared between the root daemon (running under app_process) and the app.
 * Both live in the same APK, so these classes are used on both sides of the socket.
 * Wire format: one JSON object per line.
 */
object Proto {
    const val SOCKET_NAME = "thorctl"
    const val PACKAGE = "com.thorctl"
    const val A11Y_SERVICE = "com.thorctl/com.thorctl.app.ThorAccessibilityService"
    const val ACTION_TOGGLE_MENU = "com.thorctl.action.TOGGLE_MENU"
    const val ACTION_HIDE_MENU = "com.thorctl.action.HIDE_MENU"

    // Controller routing targets
    const val TARGET_FREE = "free"
    const val TARGET_TOP = "top"
    const val TARGET_BOTTOM = "bottom"

    // LED modes
    const val LED_SOLID = "solid"
    const val LED_BREATHE = "breathe"
    const val LED_RAINBOW = "rainbow"
}

data class FanPoint(val temp: Float, val pct: Float)

data class FanProfile(
    val id: String,
    val name: String,
    /** Empty for the "auto" profile, which hands control back to the kernel thermal governor. */
    val points: List<FanPoint>,
) {
    val isAuto: Boolean get() = id == AUTO_ID
    val isBuiltin: Boolean get() = BUILTINS.any { it.id == id }

    /** Fan duty in percent for the given temperature (linear interpolation, clamped at the ends). */
    fun percentAt(temp: Float): Float {
        if (points.isEmpty()) return 0f
        val p = points.sortedBy { it.temp }
        if (temp <= p.first().temp) return p.first().pct
        if (temp >= p.last().temp) return p.last().pct
        for (i in 0 until p.size - 1) {
            val a = p[i]
            val b = p[i + 1]
            if (temp in a.temp..b.temp) {
                val f = if (b.temp == a.temp) 1f else (temp - a.temp) / (b.temp - a.temp)
                return a.pct + (b.pct - a.pct) * f
            }
        }
        return p.last().pct
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("points", JSONArray().apply {
            points.forEach { put(JSONArray().put(it.temp.toDouble()).put(it.pct.toDouble())) }
        })
    }

    companion object {
        const val AUTO_ID = "auto"

        private fun curve(vararg p: Pair<Int, Int>) = p.map { FanPoint(it.first.toFloat(), it.second.toFloat()) }

        val BUILTINS = listOf(
            FanProfile(AUTO_ID, "Auto", emptyList()),
            FanProfile("quiet", "Quiet", curve(50 to 0, 60 to 18, 70 to 32, 80 to 50, 88 to 72)),
            FanProfile("smart", "Smart", curve(42 to 0, 50 to 18, 60 to 34, 70 to 56, 80 to 82)),
            FanProfile("sport", "Sport", curve(35 to 18, 45 to 32, 55 to 55, 65 to 80, 75 to 100)),
            FanProfile("max", "Max", curve(30 to 100, 45 to 100, 60 to 100, 75 to 100, 90 to 100)),
        )

        fun fromJson(o: JSONObject): FanProfile {
            val arr = o.optJSONArray("points") ?: JSONArray()
            val pts = (0 until arr.length()).map {
                val pt = arr.getJSONArray(it)
                FanPoint(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat())
            }
            return FanProfile(o.getString("id"), o.optString("name", "Custom"), pts)
        }
    }
}

data class LedState(
    val enabled: Boolean = true,
    val mode: String = Proto.LED_SOLID,
    val left: Int = 0x4FC3F7,
    val right: Int = 0x4FC3F7,
    /** When true the right stick mirrors the left stick's color. */
    val sync: Boolean = true,
    /** 0..255 */
    val brightness: Int = 160,
    /** Animation speed multiplier, 0.25..3 */
    val speed: Float = 1f,
    /** Turn the lights off while the device is asleep. */
    val offWhenAsleep: Boolean = true,
) {
    val rightColor: Int get() = if (sync) left else right

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("mode", mode)
        put("left", left)
        put("right", right)
        put("sync", sync)
        put("brightness", brightness)
        put("speed", speed.toDouble())
        put("offWhenAsleep", offWhenAsleep)
    }

    companion object {
        fun fromJson(o: JSONObject?): LedState {
            if (o == null) return LedState()
            val d = LedState()
            return LedState(
                enabled = o.optBoolean("enabled", d.enabled),
                mode = o.optString("mode", d.mode),
                left = o.optInt("left", d.left),
                right = o.optInt("right", d.right),
                sync = o.optBoolean("sync", d.sync),
                brightness = o.optInt("brightness", d.brightness).coerceIn(0, 255),
                speed = o.optDouble("speed", d.speed.toDouble()).toFloat().coerceIn(0.25f, 3f),
                offWhenAsleep = o.optBoolean("offWhenAsleep", d.offWhenAsleep),
            )
        }
    }
}

/** What game mode does while it is on. */
data class GameOptions(
    /** Close other apps when the game starts and keep background apps closed while it runs. */
    val closeApps: Boolean = true,
    /** Hold every CPU core online at its top frequency. */
    val cpu: Boolean = true,
    /** Hold the GPU at its top frequency. */
    val gpu: Boolean = true,
    /** Fan profile while boosting; empty keeps the selected profile. */
    val fan: String = "sport",
    /** Turn the bottom screen off while the game is in front (the launcher there then frees its memory). */
    val bottomOff: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("closeApps", closeApps)
        put("cpu", cpu)
        put("gpu", gpu)
        put("fan", fan)
        put("bottomOff", bottomOff)
    }

    companion object {
        fun fromJson(o: JSONObject?): GameOptions {
            if (o == null) return GameOptions()
            val d = GameOptions()
            return GameOptions(
                closeApps = o.optBoolean("closeApps", d.closeApps),
                cpu = o.optBoolean("cpu", d.cpu),
                gpu = o.optBoolean("gpu", d.gpu),
                fan = o.optString("fan", d.fan),
                bottomOff = o.optBoolean("bottomOff", d.bottomOff),
            )
        }
    }
}

data class GameState(
    val enabled: Boolean = false,
    /** Package being boosted, or empty while waiting for a game to open. */
    val pkg: String = "",
    /** Clocks/fan currently applied (false while waiting or with the screen off). */
    val boosted: Boolean = false,
    /** Memory freed by closing other apps when the current game started. */
    val freedMb: Int = 0,
    val options: GameOptions = GameOptions(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("pkg", pkg)
        put("boosted", boosted)
        put("freedMb", freedMb)
        put("options", options.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject?): GameState {
            if (o == null) return GameState()
            return GameState(
                enabled = o.optBoolean("enabled"),
                pkg = o.optString("pkg"),
                boosted = o.optBoolean("boosted"),
                freedMb = o.optInt("freedMb"),
                options = GameOptions.fromJson(o.optJSONObject("options")),
            )
        }
    }
}

/** Full control state, pushed by the daemon whenever anything changes. */
data class DaemonState(
    val fanProfile: String,
    val profiles: List<FanProfile>,
    val brightnessTop: Float,
    val brightnessBottom: Float,
    val controller: String,
    val leds: LedState,
    val bottomOn: Boolean,
    val topDisplayId: Int,
    val bottomDisplayId: Int,
    val fanKernelMode: Boolean,
    /** Profile game mode is running instead of [fanProfile], or empty. */
    val fanOverride: String = "",
    val game: GameState = GameState(),
) {
    val activeProfile: FanProfile
        get() = profiles.firstOrNull { it.id == fanProfile } ?: FanProfile.BUILTINS.first()

    /** The profile actually driving the fan right now. */
    val runningProfile: FanProfile
        get() = profiles.firstOrNull { it.id == fanOverride } ?: activeProfile

    fun toJson(): JSONObject = JSONObject().apply {
        put("ev", "state")
        put("fanProfile", fanProfile)
        put("profiles", JSONArray().apply { profiles.forEach { put(it.toJson()) } })
        put("brightnessTop", brightnessTop.toDouble())
        put("brightnessBottom", brightnessBottom.toDouble())
        put("controller", controller)
        put("leds", leds.toJson())
        put("bottomOn", bottomOn)
        put("topDisplayId", topDisplayId)
        put("bottomDisplayId", bottomDisplayId)
        put("fanKernelMode", fanKernelMode)
        put("fanOverride", fanOverride)
        put("game", game.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): DaemonState {
            val arr = o.optJSONArray("profiles") ?: JSONArray()
            return DaemonState(
                fanProfile = o.optString("fanProfile", FanProfile.AUTO_ID),
                profiles = (0 until arr.length()).map { FanProfile.fromJson(arr.getJSONObject(it)) },
                brightnessTop = o.optDouble("brightnessTop", 0.5).toFloat(),
                brightnessBottom = o.optDouble("brightnessBottom", 0.5).toFloat(),
                controller = o.optString("controller", Proto.TARGET_FREE),
                leds = LedState.fromJson(o.optJSONObject("leds")),
                bottomOn = o.optBoolean("bottomOn", true),
                topDisplayId = o.optInt("topDisplayId", 0),
                bottomDisplayId = o.optInt("bottomDisplayId", -1),
                fanKernelMode = o.optBoolean("fanKernelMode", true),
                fanOverride = o.optString("fanOverride"),
                game = GameState.fromJson(o.optJSONObject("game")),
            )
        }
    }
}

/** Live telemetry, pushed once per second while a client is subscribed. */
data class Stats(
    val cpuLoad: Float,
    val cpuFreqMhz: Int,
    val clusterFreqsMhz: List<Int>,
    val cpuTemp: Float,
    val gpuLoad: Float,
    val gpuFreqMhz: Int,
    val gpuTemp: Float,
    val ramTotalMb: Int,
    val ramAvailMb: Int,
    val batteryLevel: Int,
    val batteryStatus: String,
    /** Power flowing out of (discharging) or into (charging) the battery, always positive. */
    val batteryWatts: Float,
    /** Power drawn from the USB port, 0 when unplugged. */
    val inputWatts: Float,
    val batteryTemp: Float,
    val fanPwm: Int,
    val fanTemp: Float,
) {
    val charging: Boolean get() = batteryStatus.equals("Charging", true)
    val plugged: Boolean get() = inputWatts > 0.05f
    val fanPercent: Int get() = Math.round(fanPwm * 100f / 255f)

    fun toJson(): JSONObject = JSONObject().apply {
        put("ev", "stats")
        put("cpuLoad", cpuLoad.toDouble())
        put("cpuFreqMhz", cpuFreqMhz)
        put("clusterFreqsMhz", JSONArray(clusterFreqsMhz))
        put("cpuTemp", cpuTemp.toDouble())
        put("gpuLoad", gpuLoad.toDouble())
        put("gpuFreqMhz", gpuFreqMhz)
        put("gpuTemp", gpuTemp.toDouble())
        put("ramTotalMb", ramTotalMb)
        put("ramAvailMb", ramAvailMb)
        put("batteryLevel", batteryLevel)
        put("batteryStatus", batteryStatus)
        put("batteryWatts", batteryWatts.toDouble())
        put("inputWatts", inputWatts.toDouble())
        put("batteryTemp", batteryTemp.toDouble())
        put("fanPwm", fanPwm)
        put("fanTemp", fanTemp.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject): Stats {
            val cf = o.optJSONArray("clusterFreqsMhz") ?: JSONArray()
            return Stats(
                cpuLoad = o.optDouble("cpuLoad").toFloat(),
                cpuFreqMhz = o.optInt("cpuFreqMhz"),
                clusterFreqsMhz = (0 until cf.length()).map { cf.getInt(it) },
                cpuTemp = o.optDouble("cpuTemp").toFloat(),
                gpuLoad = o.optDouble("gpuLoad").toFloat(),
                gpuFreqMhz = o.optInt("gpuFreqMhz"),
                gpuTemp = o.optDouble("gpuTemp").toFloat(),
                ramTotalMb = o.optInt("ramTotalMb"),
                ramAvailMb = o.optInt("ramAvailMb"),
                batteryLevel = o.optInt("batteryLevel"),
                batteryStatus = o.optString("batteryStatus"),
                batteryWatts = o.optDouble("batteryWatts").toFloat(),
                inputWatts = o.optDouble("inputWatts").toFloat(),
                batteryTemp = o.optDouble("batteryTemp").toFloat(),
                fanPwm = o.optInt("fanPwm"),
                fanTemp = o.optDouble("fanTemp").toFloat(),
            )
        }
    }
}

/**
 * Same perceptual mapping SystemUI uses for its brightness slider (BrightnessUtils, HLG curve):
 * slider position 0..1 <-> linear brightness 0..1.
 */
object BrightnessCurve {
    private const val R = 0.5f
    private const val A = 0.17883277f
    private const val B = 0.28466892f
    private const val C = 0.55991073f
    private const val MIN = 0.004f

    fun toLinear(position: Float): Float {
        val v = position.coerceIn(0f, 1f)
        val ret = if (v <= R) (v / R) * (v / R) else exp((v - C) / A) + B
        return (ret / 12f).coerceIn(MIN, 1f)
    }

    fun toPosition(linear: Float): Float {
        val n = linear.coerceIn(0f, 1f) * 12f
        val ret = if (n <= 1f) R * sqrt(n) else A * ln(n - B) + C
        return ret.coerceIn(0f, 1f)
    }
}
