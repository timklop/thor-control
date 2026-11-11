package com.thorctl.daemon

/**
 * Tracks which logical display is the top (6") and bottom (3.92") panel and controls their
 * brightness and power independently.
 *
 * Independent brightness requires the bottom panel to not be a "brightness follower" of the top
 * one; the Magisk module ships a display_layout_configuration.xml without leadDisplayAddress.
 */
internal class DisplayController {
    @Volatile
    var topId = 0
        private set

    @Volatile
    var bottomId = -1
        private set

    @Volatile
    var topUniqueId = ""
        private set

    @Volatile
    var bottomUniqueId = ""
        private set

    /** True while we hold the bottom panel off. */
    @Volatile
    var bottomForcedOff = false
        private set

    val bottomOn: Boolean get() = !bottomForcedOff

    fun refresh() {
        val internal = try {
            SystemServices.displays().filter { it.type == TYPE_INTERNAL }
        } catch (e: Throwable) {
            logw("display refresh failed", e)
            return
        }
        when {
            internal.size >= 2 -> {
                val sorted = internal.sortedBy { it.width.toLong() * it.height }
                val bottom = sorted.first()
                val top = sorted.last()
                bottomId = bottom.id
                bottomUniqueId = bottom.uniqueId
                topId = top.id
                topUniqueId = top.uniqueId
            }
            internal.size == 1 -> {
                val only = internal.first()
                if (bottomUniqueId.isNotEmpty() && only.uniqueId == bottomUniqueId) {
                    bottomId = only.id
                    topId = -1
                } else {
                    topId = only.id
                    topUniqueId = only.uniqueId
                    bottomId = -1
                }
            }
        }
    }

    private fun idFor(which: String): Int = if (which == "bottom") bottomId else topId

    fun getBrightness(which: String): Float {
        val id = idFor(which)
        if (id < 0) return 0f
        return try {
            SystemServices.getBrightness(id)
        } catch (e: Throwable) {
            0f
        }
    }

    fun setBrightness(which: String, value: Float) {
        val id = idFor(which)
        if (id < 0) return
        try {
            SystemServices.setBrightness(id, value.coerceIn(MIN_BRIGHTNESS, 1f))
        } catch (e: Throwable) {
            logw("setBrightness($which) failed", e)
        }
    }

    fun setBottomPower(on: Boolean): Boolean {
        val id = bottomId
        if (id < 0) return false
        val ok = try {
            SystemServices.requestDisplayPower(id, if (on) STATE_UNKNOWN else STATE_OFF)
        } catch (e: Throwable) {
            logw("requestDisplayPower failed", e)
            false
        }
        if (ok) {
            bottomForcedOff = !on
            setBottomTouch(on)
        }
        logi("bottom display ${if (on) "on" else "off"} -> $ok")
        return ok
    }

    /** The power override can be dropped when the device wakes; put it back. */
    fun reassertBottomOff() {
        if (!bottomForcedOff || bottomId < 0) return
        try {
            SystemServices.requestDisplayPower(bottomId, STATE_OFF)
        } catch (_: Throwable) {
        }
    }

    // ---- Bottom panel watchdog ------------------------------------------------------------
    // LineageOS sometimes boots with the bottom panel dark: the framework and SurfaceFlinger
    // say the display is ON, but the kernel DSI connector never got enabled. A framework power
    // cycle (off -> back to policy) makes HWC commit the panel again.

    private val bottomConnector: String? = java.io.File("/sys/class/drm").listFiles()
        ?.filter { it.name.matches(Regex("card\\d+-DSI-\\d+")) }
        ?.sortedBy { it.name }
        ?.takeIf { it.size >= 2 }
        ?.last()?.path

    private var stuckChecks = 0
    private var lastHeal = 0L
    private var healsThisWake = 0

    fun onWake() {
        stuckChecks = 0
        healsThisWake = 0
    }

    /** Called every couple of seconds while the device is awake. */
    fun healBottomPanel() {
        val conn = bottomConnector ?: return
        val id = bottomId
        if (id < 0 || bottomForcedOff) {
            stuckChecks = 0
            return
        }
        val frameworkOn = try {
            SystemServices.displays().firstOrNull { it.id == id }?.state == STATE_ON
        } catch (_: Throwable) {
            false
        }
        val panelEnabled = Sys.read("$conn/enabled") == "enabled"
        if (!frameworkOn || panelEnabled) {
            stuckChecks = 0
            return
        }
        if (++stuckChecks < 2) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastHeal < 15_000 || healsThisWake >= 3) return
        lastHeal = now
        healsThisWake++
        stuckChecks = 0
        logi("bottom panel stuck (display ON, ${conn.substringAfterLast('/')} not enabled); power-cycling it")
        try {
            SystemServices.requestDisplayPower(id, STATE_OFF)
            Thread.sleep(500)
            SystemServices.requestDisplayPower(id, STATE_UNKNOWN)
        } catch (e: Throwable) {
            logw("bottom panel power cycle failed", e)
        }
    }

    /** Ignore touches on the dark panel so nothing invisible gets tapped. */
    fun setBottomTouch(enabled: Boolean) {
        try {
            SystemServices.inputDevices()
                .filter { it.name == BOTTOM_TOUCH_NAME }
                .forEach { SystemServices.setInputDeviceEnabled(it.id, enabled) }
        } catch (e: Throwable) {
            logw("toggle bottom touch failed", e)
        }
    }

    companion object {
        const val TYPE_INTERNAL = 1
        const val STATE_UNKNOWN = 0
        const val STATE_OFF = 1
        const val STATE_ON = 2
        const val MIN_BRIGHTNESS = 0.004f
        const val BOTTOM_TOUCH_NAME = "fts_ts_secondary"
    }
}
