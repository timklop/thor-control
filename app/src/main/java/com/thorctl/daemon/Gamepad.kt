package com.thorctl.daemon

import android.os.SystemClock
import android.system.Os
import com.thorctl.shared.GamepadHid
import com.thorctl.shared.GamepadState
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import kotlin.concurrent.thread

/**
 * Gamepad mode: the built-in controller drives another device as a standard HID gamepad.
 *
 * The controller is grabbed exclusively (nothing reaches Android) and every evdev frame becomes a
 * HID report the moment its SYN_REPORT arrives:
 *
 *  - USB: an f_hid function is added next to adb in the USB gadget, so a computer sees a
 *    composite device with a gamepad. A writer thread always sends the newest report; f_hid only
 *    blocks while the previous one waits for the host's next poll, so nothing queues up.
 *  - Bluetooth: the app owns the Bluetooth HID device registration (it needs a Context). Reports
 *    go to it over the socket, newest-wins and at most one per [BT_INTERVAL_MS], so the radio
 *    never builds a backlog.
 *
 * While gamepad mode is on, a kernel wakelock keeps the SoC out of suspend, so the screens can
 * sleep (by themselves, or right away with "screens off") without cutting the connection.
 */
internal class Gamepad(
    private val input: InputWatcher,
    /** Bluetooth report to the app (base64 payload). */
    private val sendToApp: (String) -> Unit,
    private val onChanged: () -> Unit,
    private val sleepScreens: () -> Unit,
    private val wakeScreens: () -> Unit,
) {
    @Volatile
    var state = GamepadState()
        private set

    /** Both screens were put to sleep by us when the host connected. */
    @Volatile
    var screensSleptByUs = false
        private set

    private var grabbedAt = 0L

    fun init(transport: String, screensOff: Boolean) {
        state = state.copy(transport = transport, screensOff = screensOff)
        // A crash or reboot in gamepad mode can leave the USB function linked: take it out.
        if (usbLinked()) thread(name = "gamepad-usb-cleanup", isDaemon = true) { usbTeardown() }
    }

    // ---- Control ------------------------------------------------------------------------------

    @Synchronized
    fun setOn(on: Boolean) {
        if (on == state.on) return
        if (on) start() else stop()
        onChanged()
    }

    @Synchronized
    fun setTransport(t: String) {
        if (t == state.transport || (t != GamepadState.USB && t != GamepadState.BLUETOOTH)) return
        val wasOn = state.on
        if (wasOn) stop()
        state = state.copy(transport = t)
        if (wasOn) start()
        onChanged()
    }

    @Synchronized
    fun setScreensOff(on: Boolean) {
        state = state.copy(screensOff = on)
        if (on && state.on && state.connected && !screensSleptByUs) sleepNow()
        onChanged()
    }

    /** From the app: the Bluetooth host connected or disconnected. */
    @Synchronized
    fun onBluetooth(connected: Boolean, host: String) {
        if (!state.on || state.transport != GamepadState.BLUETOOTH) return
        setConnected(connected, if (connected) host else "")
    }

    private fun start() {
        state = state.copy(on = true, connected = false, host = "", error = "")
        wakelock(true)
        if (state.transport == GamepadState.USB) {
            thread(name = "gamepad-usb-setup", isDaemon = true) {
                val err = usbSetup()
                synchronized(this) {
                    if (state.on && state.transport == GamepadState.USB) {
                        if (err != null) state = state.copy(error = err)
                        if (err == null) startUsbWriter()
                        onChanged()
                    }
                }
            }
        }
        if (state.transport == GamepadState.BLUETOOTH) {
            // The app registers the HID device once the adapter is on.
            thread(name = "gamepad-bt-on", isDaemon = true) { Sys.sh("cmd bluetooth_manager enable") }
        }
        frame.reset()
        grab()
        logi("gamepad mode on (${state.transport})")
    }

    private fun stop() {
        val t = state.transport
        input.releaseGamepad()
        // Let the host see everything released before it goes away.
        submit(GamepadHid.neutral())
        if (t == GamepadState.USB) {
            usbWriterOn = false
            synchronized(usbLock) { (usbLock as Object).notifyAll() }
            thread(name = "gamepad-usb-teardown", isDaemon = true) { usbTeardown() }
        }
        state = state.copy(on = false, connected = false, host = "", error = "")
        if (screensSleptByUs) {
            screensSleptByUs = false
            wakeScreens()
        }
        wakelock(false)
        logi("gamepad mode off")
    }

    private fun grab() {
        if (!input.grabForGamepad(::onEvent)) logw("gamepad: could not grab the controller")
        grabbedAt = SystemClock.elapsedRealtime()
        // Start from what is held right now.
        val (keys, abs) = input.controllerSnapshot()
        frame.load(keys, abs)
        submit(frame.build())
    }

    private fun setConnected(connected: Boolean, host: String) {
        if (connected == state.connected && host == state.host) return
        state = state.copy(connected = connected, host = host)
        logi("gamepad host ${if (connected) "connected ($host)" else "disconnected"}")
        if (connected) {
            if (state.screensOff) sleepNow()
        } else if (screensSleptByUs) {
            // Host gone: don't leave the Thor dark.
            screensSleptByUs = false
            wakeScreens()
        }
        onChanged()
    }

    private fun sleepNow() {
        screensSleptByUs = true
        sleepScreens()
    }

    /** Daemon exiting (module disabled): give USB and the controller back right now. */
    @Synchronized
    fun shutdown() {
        if (!state.on) return
        input.releaseGamepad()
        usbWriterOn = false
        if (state.transport == GamepadState.USB) usbTeardown()
        state = state.copy(on = false, connected = false, host = "")
        wakelock(false)
    }

    /** The user woke the screens (power / AYN): leave them on. */
    fun onScreensWoken() {
        screensSleptByUs = false
    }

    /** Every couple of seconds from the daemon's housekeeping. */
    @Synchronized
    fun tick() {
        if (!state.on) return
        wakelock(true)
        // The controller node can come back as a new device (after a controller MCU reset).
        if (!input.gamepadGrabbed() && SystemClock.elapsedRealtime() - grabbedAt > 3000 && input.hasController) {
            input.releaseGamepad()
            grab()
        }
        if (state.transport == GamepadState.USB && state.error.isEmpty()) {
            if (!usbLinked()) {
                // Android rebuilt the USB configuration (a mode change in Settings): add ours back.
                logi("gamepad: USB function was removed, adding it again")
                thread(name = "gamepad-usb-setup", isDaemon = true) { usbSetup() }
            }
            setConnected(Sys.read("$UDC_CLASS/${udcName()}/state")?.trim() == "configured", "")
        }
    }

    // ---- Reports ------------------------------------------------------------------------------

    private val frame = Frame()

    /** Grab thread: one raw evdev event. */
    private fun onEvent(type: Int, code: Int, value: Int) {
        when (type) {
            InputWatcher.EV_KEY -> frame.key(code, value != 0)
            InputWatcher.EV_ABS -> frame.abs(code, value)
            InputWatcher.EV_SYN -> if (code == SYN_REPORT) {
                val r = frame.build()
                if (!r.contentEquals(lastSubmitted)) submit(r)
            }
        }
    }

    @Volatile
    private var lastSubmitted = ByteArray(0)

    private fun submit(r: ByteArray) {
        lastSubmitted = r
        when (state.transport) {
            GamepadState.USB -> synchronized(usbLock) {
                usbPending = r
                (usbLock as Object).notifyAll()
            }
            else -> btSubmit(r)
        }
    }

    /** Controller state, folded from evdev events into the report layout of [GamepadHid]. */
    private class Frame {
        private val held = HashSet<Int>()
        private var lx = 0
        private var ly = 0
        private var rx = 0
        private var ry = 0
        private var lt = 0
        private var rt = 0
        private var hx = 0
        private var hy = 0

        fun reset() {
            held.clear()
            lx = 0; ly = 0; rx = 0; ry = 0; lt = 0; rt = 0; hx = 0; hy = 0
        }

        fun load(keys: Set<Int>, abs: IntArray) {
            reset()
            held += keys
            lx = abs[ABS_X]; ly = abs[ABS_Y]; rx = abs[ABS_RX]; ry = abs[ABS_RY]
            lt = abs[InputWatcher.ABS_Z]; rt = abs[InputWatcher.ABS_RZ]
            hx = abs[InputWatcher.ABS_HAT0X]; hy = abs[InputWatcher.ABS_HAT0Y]
        }

        fun key(code: Int, down: Boolean) {
            if (down) held += code else held -= code
        }

        fun abs(code: Int, v: Int) {
            when (code) {
                ABS_X -> lx = v
                ABS_Y -> ly = v
                ABS_RX -> rx = v
                ABS_RY -> ry = v
                InputWatcher.ABS_Z -> lt = v
                InputWatcher.ABS_RZ -> rt = v
                InputWatcher.ABS_HAT0X -> hx = v
                InputWatcher.ABS_HAT0Y -> hy = v
            }
        }

        fun build(): ByteArray {
            var buttons = 0
            for (c in held) {
                val bit = when (c) {
                    in InputWatcher.BTN_SOUTH..InputWatcher.BTN_THUMBR -> c - InputWatcher.BTN_SOUTH
                    InputWatcher.KEY_HOME -> BIT_HOME
                    InputWatcher.KEY_BACK -> BIT_BACK
                    else -> -1
                }
                if (bit >= 0) buttons = buttons or (1 shl bit)
            }
            val hat = when {
                hy < 0 && hx == 0 -> 0
                hy < 0 && hx > 0 -> 1
                hy == 0 && hx > 0 -> 2
                hy > 0 && hx > 0 -> 3
                hy > 0 && hx == 0 -> 4
                hy > 0 && hx < 0 -> 5
                hy == 0 && hx < 0 -> 6
                hy < 0 && hx < 0 -> 7
                else -> 8
            }
            val b = ByteArray(GamepadHid.PAYLOAD)
            b[0] = buttons.toByte()
            b[1] = (buttons shr 8).toByte()
            b[2] = hat.toByte()
            put16(b, 3, lx)
            put16(b, 5, ly)
            put16(b, 7, rx)
            put16(b, 9, ry)
            b[11] = trigger(lt).toByte()
            b[12] = trigger(rt).toByte()
            return b
        }

        private fun put16(b: ByteArray, at: Int, v: Int) {
            val c = v.coerceIn(-32767, 32767)
            b[at] = c.toByte()
            b[at + 1] = (c shr 8).toByte()
        }

        /** The analog triggers rest a few counts above 0; a small dead zone keeps them at 0. */
        private fun trigger(v: Int): Int =
            ((v - TRIGGER_DEAD) * 255 / (255 - TRIGGER_DEAD)).coerceIn(0, 255)
    }

    // ---- USB ----------------------------------------------------------------------------------

    private val usbLock = Any()
    private var usbPending: ByteArray? = null

    @Volatile
    private var usbWriterOn = false

    private fun startUsbWriter() {
        if (usbWriterOn) return
        usbWriterOn = true
        thread(name = "gamepad-usb-tx", isDaemon = true) { usbWriteLoop() }
    }

    private fun usbWriteLoop() {
        val frameBuf = ByteArray(1 + GamepadHid.PAYLOAD)
        frameBuf[0] = GamepadHid.REPORT_ID.toByte()
        while (usbWriterOn) {
            val out = try {
                FileOutputStream(HIDG)
            } catch (_: Exception) {
                Thread.sleep(300) // node not there yet (the gadget is still binding)
                continue
            }
            out.use { o ->
                // A fresh host: send the current state first.
                var next: ByteArray? = lastSubmitted.takeIf { it.size == GamepadHid.PAYLOAD }
                while (usbWriterOn) {
                    val r = next ?: synchronized(usbLock) {
                        while (usbPending == null && usbWriterOn) (usbLock as Object).wait(1000)
                        usbPending.also { usbPending = null }
                    } ?: continue
                    next = null
                    System.arraycopy(r, 0, frameBuf, 1, GamepadHid.PAYLOAD)
                    try {
                        // Returns as soon as the report is queued; only blocks while the previous
                        // one is still waiting for the host's poll (newer reports replace usbPending).
                        o.write(frameBuf)
                    } catch (_: Exception) {
                        // Not configured (no host) or the host went away: retry shortly.
                        synchronized(usbLock) { if (usbPending == null) usbPending = r }
                        Thread.sleep(250)
                        break
                    }
                }
            }
        }
    }

    private fun udcName(): String =
        Sys.read("$GADGET/UDC")?.trim()?.takeIf { it.isNotEmpty() }
            ?: File(UDC_CLASS).list()?.firstOrNull()
            ?: ""

    private fun configDir(): File? = File("$GADGET/configs").listFiles()?.firstOrNull { it.isDirectory }

    private fun usbLinked(): Boolean {
        val c = configDir() ?: return false
        return try {
            Os.readlink(File(c, LINK_NAME).path).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    /** Adds the HID function to the gadget and rebinds it. Returns an error message or null. */
    private fun usbSetup(): String? {
        if (!File(GADGET).isDirectory) return "USB gadget not available"
        val cfg = configDir() ?: return "USB gadget has no configuration"
        if (usbLinked()) return null
        val fn = File(FUNCTION)
        try {
            if (!fn.isDirectory && !fn.mkdir()) return "This kernel has no USB HID gadget support"
            File(fn, "protocol").writeText("0")
            File(fn, "subclass").writeText("0")
            File(fn, "report_length").writeText((1 + GamepadHid.PAYLOAD).toString())
            FileOutputStream(File(fn, "report_desc")).use { it.write(GamepadHid.DESCRIPTOR) }
        } catch (e: Exception) {
            logw("gamepad: HID function set-up failed", e)
            return "USB HID set-up failed"
        }
        val udc = udcName()
        if (udc.isEmpty()) return "No USB controller"
        // The configuration can only change while unbound. Android's USB HAL rebinds the gadget
        // by itself when adb comes back; any bind after the link includes the gamepad.
        unbind()
        try {
            Os.symlink(fn.path, File(cfg, LINK_NAME).path)
        } catch (e: Exception) {
            logw("gamepad: linking the HID function failed", e)
            bind(udc)
            return "USB HID set-up failed"
        }
        bind(udc)
        logi("gamepad: USB HID function added (${cfg.name}, $udc)")
        return null
    }

    private fun usbTeardown() {
        val cfg = configDir() ?: return
        val link = File(cfg, LINK_NAME)
        val udc = udcName()
        if (usbLinked()) {
            unbind()
            try {
                Os.remove(link.path)
            } catch (e: Exception) {
                logw("gamepad: unlinking the HID function failed", e)
            }
            if (udc.isNotEmpty()) bind(udc)
        }
        File(FUNCTION).delete() // rmdir; fails harmlessly if something still links it
        logi("gamepad: USB HID function removed")
    }

    private fun unbind() {
        Sys.write("$GADGET/UDC", "\n")
        Thread.sleep(100)
    }

    /** Binds the gadget, unless Android's USB HAL already did (it reacts to adb reopening). */
    private fun bind(udc: String) {
        repeat(40) {
            if (!Sys.read("$GADGET/UDC")?.trim().isNullOrEmpty()) return
            Sys.write("$GADGET/UDC", udc)
            Thread.sleep(100)
        }
        logw("gamepad: USB gadget did not bind")
    }

    // ---- Bluetooth (sent through the app) -----------------------------------------------------

    private val btLock = Any()
    private var btPending: ByteArray? = null
    private var btLast = 0L
    private var btScheduled = false

    private fun btSubmit(r: ByteArray) {
        synchronized(btLock) {
            btPending = r
            val wait = btLast + BT_INTERVAL_MS - SystemClock.uptimeMillis()
            if (wait <= 0) {
                btFlushLocked()
            } else if (!btScheduled) {
                btScheduled = true
                thread(name = "gamepad-bt-tx", isDaemon = true) {
                    Thread.sleep(wait)
                    synchronized(btLock) {
                        btScheduled = false
                        btFlushLocked()
                    }
                }
            }
        }
    }

    private fun btFlushLocked() {
        val r = btPending ?: return
        btPending = null
        btLast = SystemClock.uptimeMillis()
        sendToApp("""{"ev":"gp_report","r":"${Base64.getEncoder().encodeToString(r)}"}""")
    }

    // ---- Wakelock -----------------------------------------------------------------------------

    /** Timed, so it lapses by itself if the daemon dies; refreshed on every [tick]. */
    private fun wakelock(on: Boolean) {
        if (on) Sys.write("/sys/power/wake_lock", "$WAKELOCK $WAKELOCK_NS") else Sys.write("/sys/power/wake_unlock", WAKELOCK)
    }

    companion object {
        const val GADGET = "/config/usb_gadget/g1"
        const val UDC_CLASS = "/sys/class/udc"
        const val FUNCTION = "$GADGET/functions/hid.thorpad"

        /** Not "functionN": Android's USB HAL unlinks those when it rebuilds the configuration. */
        const val LINK_NAME = "thor_gamepad"
        const val HIDG = "/dev/hidg0"

        const val SYN_REPORT = 0
        const val ABS_X = 0x00
        const val ABS_Y = 0x01
        const val ABS_RX = 0x03
        const val ABS_RY = 0x04
        const val BIT_HOME = 12 // the Home button is the gamepad's guide button (BTN_MODE)
        const val BIT_BACK = 15
        const val TRIGGER_DEAD = 10

        /** 125 reports a second at most over Bluetooth, the newest always wins. */
        const val BT_INTERVAL_MS = 8L

        const val WAKELOCK = "thorctl_gamepad"
        const val WAKELOCK_NS = 60_000_000_000L
    }
}
