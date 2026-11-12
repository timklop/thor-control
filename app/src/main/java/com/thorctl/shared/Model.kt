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

/**
 * Gamepad mode: the built-in controller becomes a standard HID gamepad for another device, over
 * USB (the Thor as a USB device) or Bluetooth (the Thor as a Bluetooth HID device).
 */
data class GamepadState(
    val on: Boolean = false,
    /** [USB] or [BLUETOOTH]; kept when gamepad mode is off. */
    val transport: String = USB,
    /** A host is using the gamepad (USB: enumerated by a computer; Bluetooth: connected). */
    val connected: Boolean = false,
    /** Name of the connected Bluetooth host, or empty. */
    val host: String = "",
    /** Darken both screens while a host is connected (the controller keeps working). */
    val screensOff: Boolean = true,
    /** The screens are dark right now: top panel off, bottom panel black (drawn by the app). */
    val dark: Boolean = false,
    /** USB set-up failed (no gadget support or the port is in host mode). */
    val error: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("on", on)
        put("transport", transport)
        put("connected", connected)
        put("host", host)
        put("screensOff", screensOff)
        put("dark", dark)
        put("error", error)
    }

    companion object {
        const val USB = "usb"
        const val BLUETOOTH = "bt"

        fun fromJson(o: JSONObject?): GamepadState {
            if (o == null) return GamepadState()
            val d = GamepadState()
            return GamepadState(
                on = o.optBoolean("on"),
                transport = o.optString("transport", d.transport).takeIf { it == USB || it == BLUETOOTH } ?: d.transport,
                connected = o.optBoolean("connected"),
                host = o.optString("host"),
                screensOff = o.optBoolean("screensOff", d.screensOff),
                dark = o.optBoolean("dark"),
                error = o.optString("error"),
            )
        }
    }
}

/**
 * The gamepad as hosts see it: a Google Stadia Controller (USB vendor 18D1, product 9400,
 * version 1.00) with the Stadia's report descriptor and report layout.
 *
 * A generic HID gamepad works in SDL, browsers and on Linux, but macOS only hands controllers to
 * games (the GameController framework, System Settings > Game Controllers) when it knows the
 * vendor and product ID. The Stadia controller is a plain HID gamepad, with no handshake, that
 * macOS, SDL (its HIDAPI driver), Chrome, Linux and Android all know. It uses Google's vendor
 * ID, which the Thor's USB already has. Over Bluetooth the same IDs come from the Thor's Device
 * ID record (`bluetooth.device_id.*` in magisk/system.prop).
 *
 * Input report 3, 10 bytes after the report ID:
 *  - [0] D-pad hat, 0 = up, clockwise, 8 = centred.
 *  - [1] bit 0 Capture (Share on macOS), 1 Assistant, 2 L2 and 3 R2 (digital), 4 Home,
 *        5 Menu (Start), 6 Options (Select), 7 R3.
 *  - [2] bit 0 L3, 1 R1, 2 L1, 3 Y, 4 X, 5 B, 6 A.
 *  - [3..6] left stick X / Y, right stick X / Y, 1..255 with 128 centred.
 *  - [7] L2 and [8] R2, analog 0..255.
 *  - [9] volume up / down / play (unused).
 *
 * Button usages follow Android: A = 1, B = 2, X = 4, Y = 5, L1 = 7, R1 = 8, Select = 11,
 * Start = 12, Home = 13, L3 = 14, R3 = 15, so Linux hosts get the usual BTN_* codes. Home and
 * Select carry each other's usage relative to the bits SDL reads, which is what makes all three of
 * macOS, SDL and Linux agree (checked on a Mac: Home opens Games, Select is Options).
 *
 * Output report 5 (rumble, two 16-bit motors) is declared because Linux's Stadia driver needs
 * it. Hosts may send it; it is read and dropped.
 */
object GamepadHid {
    const val VENDOR_ID = 0x18D1
    const val PRODUCT_ID = 0x9400
    const val VERSION = 0x0100

    const val REPORT_ID = 3

    /** Bytes after the report ID. */
    const val PAYLOAD = 10

    // Bits of byte 1 and byte 2.
    const val B1_CAPTURE = 0x01
    const val B1_L2 = 0x04
    const val B1_R2 = 0x08
    const val B1_HOME = 0x10
    const val B1_START = 0x20
    const val B1_SELECT = 0x40
    const val B1_R3 = 0x80
    const val B2_L3 = 0x01
    const val B2_R1 = 0x02
    const val B2_L1 = 0x04
    const val B2_Y = 0x08
    const val B2_X = 0x10
    const val B2_B = 0x20
    const val B2_A = 0x40

    val DESCRIPTOR: ByteArray = intArrayOf(
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x05, // Usage (Game Pad)
        0xA1, 0x01, // Collection (Application)
        0x85, REPORT_ID, //   Report ID (3)
        0x05, 0x01, //   Usage Page (Generic Desktop)
        0x15, 0x00, //   Logical Minimum (0)
        0x75, 0x04, //   Report Size (4)
        0x95, 0x01, //   Report Count (1)
        0x25, 0x07, //   Logical Maximum (7)
        0x46, 0x3B, 0x01, //   Physical Maximum (315)
        0x65, 0x14, //   Unit (Degrees)
        0x09, 0x39, //   Usage (Hat Switch)
        0x81, 0x42, //   Input (Data, Var, Abs, Null State)
        0x45, 0x00, //   Physical Maximum (0)
        0x65, 0x00, //   Unit (None)
        0x75, 0x01, //   Report Size (1)
        0x95, 0x04, //   Report Count (4)
        0x81, 0x01, //   Input (Const): padding
        0x05, 0x09, //   Usage Page (Button)
        0x15, 0x00, //   Logical Minimum (0)
        0x25, 0x01, //   Logical Maximum (1)
        0x75, 0x01, //   Report Size (1)
        0x95, 0x0F, //   Report Count (15), byte 1 bit 0 to byte 2 bit 6:
        0x09, 0x12, //     Button 18: Capture
        0x09, 0x11, //     Button 17: Assistant
        0x09, 0x14, //     Button 20: L2
        0x09, 0x13, //     Button 19: R2
        0x09, 0x0D, //     Button 13: Home
        0x09, 0x0C, //     Button 12: Start
        0x09, 0x0B, //     Button 11: Select
        0x09, 0x0F, //     Button 15: R3
        0x09, 0x0E, //     Button 14: L3
        0x09, 0x08, //     Button 8: R1
        0x09, 0x07, //     Button 7: L1
        0x09, 0x05, //     Button 5: Y
        0x09, 0x04, //     Button 4: X
        0x09, 0x02, //     Button 2: B
        0x09, 0x01, //     Button 1: A
        0x81, 0x02, //   Input (Data, Var, Abs)
        0x75, 0x01, //   Report Size (1)
        0x95, 0x01, //   Report Count (1)
        0x81, 0x01, //   Input (Const): padding
        0x05, 0x01, //   Usage Page (Generic Desktop)
        0x15, 0x01, //   Logical Minimum (1)
        0x26, 0xFF, 0x00, //   Logical Maximum (255)
        0x09, 0x01, //   Usage (Pointer)
        0xA1, 0x00, //   Collection (Physical)
        0x09, 0x30, //     Usage (X)
        0x09, 0x31, //     Usage (Y)
        0x75, 0x08, //     Report Size (8)
        0x95, 0x02, //     Report Count (2)
        0x81, 0x02, //     Input (Data, Var, Abs)
        0xC0, //   End Collection
        0x09, 0x01, //   Usage (Pointer)
        0xA1, 0x00, //   Collection (Physical)
        0x09, 0x32, //     Usage (Z)
        0x09, 0x35, //     Usage (Rz)
        0x75, 0x08, //     Report Size (8)
        0x95, 0x02, //     Report Count (2)
        0x81, 0x02, //     Input (Data, Var, Abs)
        0xC0, //   End Collection
        0x05, 0x02, //   Usage Page (Simulation Controls)
        0x75, 0x08, //   Report Size (8)
        0x95, 0x02, //   Report Count (2)
        0x15, 0x00, //   Logical Minimum (0)
        0x26, 0xFF, 0x00, //   Logical Maximum (255)
        0x09, 0xC5, //   Usage (Brake): L2
        0x09, 0xC4, //   Usage (Accelerator): R2
        0x81, 0x02, //   Input (Data, Var, Abs)
        0x05, 0x0C, //   Usage Page (Consumer)
        0x15, 0x00, //   Logical Minimum (0)
        0x25, 0x01, //   Logical Maximum (1)
        0x09, 0xE9, //   Usage (Volume Increment)
        0x09, 0xEA, //   Usage (Volume Decrement)
        0x75, 0x01, //   Report Size (1)
        0x95, 0x02, //   Report Count (2)
        0x81, 0x02, //   Input (Data, Var, Abs)
        0x09, 0xCD, //   Usage (Play/Pause)
        0x95, 0x01, //   Report Count (1)
        0x81, 0x02, //   Input (Data, Var, Abs)
        0x95, 0x05, //   Report Count (5)
        0x81, 0x01, //   Input (Const): padding
        0x85, 0x05, //   Report ID (5)
        0x06, 0x0F, 0x00, //   Usage Page (Physical Interface Device)
        0x09, 0x97, //   Usage (DC Enable Actuators)
        0x75, 0x10, //   Report Size (16)
        0x95, 0x02, //   Report Count (2)
        0x27, 0xFF, 0xFF, 0x00, 0x00, //   Logical Maximum (65535)
        0x91, 0x02, //   Output (Data, Var, Abs): rumble, strong and weak motor
        0xC0, // End Collection
    ).map { it.toByte() }.toByteArray()

    /** Everything released, sticks centred. */
    fun neutral(): ByteArray = ByteArray(PAYLOAD).also {
        it[0] = 8
        for (i in 3..6) it[i] = 128.toByte()
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
    val gamepad: GamepadState = GamepadState(),
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
        put("gamepad", gamepad.toJson())
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
                gamepad = GamepadState.fromJson(o.optJSONObject("gamepad")),
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
