package com.thorctl.daemon

import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Raw evdev input handling, below the Android input pipeline:
 *
 *  - AYN button (KEY_F24 on gpio-keys) acts as a modifier. While it is held the built-in
 *    controller is grabbed exclusively (EVIOCGRAB), so combo presses like AYN+B never reach the
 *    game. Releasing AYN without using a combo is a "tap" (opens/closes the menu).
 *  - Physical Home on the controller: short press = Home, long press = app switcher.
 *    The module removes HOME from the controller's key layout so Android does not also act on it;
 *    without that remap we only add the app switcher on long press.
 *  - While Thor Control's recent apps screen is open, the controller is grabbed the same way and
 *    its buttons drive that screen ([grabController]); the app underneath sees nothing.
 */
internal class InputWatcher(private val listener: Listener) {
    interface Listener {
        fun onAynTap()
        fun onCombo(combo: Combo)
        fun onHomeDown()
        fun onHomeSingle()
        fun onShoulder()
        fun onHomeLong()
    }

    enum class Combo { UP, DOWN, LEFT, RIGHT, B, X, Y, L1, R1, L2, R2 }

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "input-worker") }
    private val pad = Pad()

    @Volatile
    private var aynDown = false

    @Volatile
    private var comboUsed = false

    /** True when Android no longer maps the controller's KEY_HOME (module key layout applied). */
    val homeRemapped: Boolean = run {
        val kl = Sys.read(CONTROLLER_KL) ?: return@run false
        !Regex("^key\\s+$KEY_HOME\\s+HOME\\b", RegexOption.MULTILINE).containsMatchIn(kl)
    }

    /** Exclusive controller grab; every raw event goes to [handler] until [releaseController]. */
    fun grabController(handler: (type: Int, code: Int, value: Int) -> Unit): Boolean =
        pad.beginGrab(Owner.RECENTS, handler, 0)

    fun releaseController() = pad.endGrab(Owner.RECENTS)

    /**
     * Gamepad mode: the controller is grabbed for as long as it lasts and every raw event goes
     * to [handler]. While AYN is held, events go to combo detection instead, so AYN shortcuts
     * keep working (and never reach the other device).
     */
    fun grabForGamepad(handler: (type: Int, code: Int, value: Int) -> Unit): Boolean =
        pad.beginGrab(Owner.GAMEPAD, { t, c, v -> if (aynDown) pad.detect(t, c, v) else handler(t, c, v) }, 0)

    fun releaseGamepad() = pad.endGrab(Owner.GAMEPAD)

    /** Gamepad mode still holds a live grab (false if the controller node went away). */
    fun gamepadGrabbed(): Boolean = pad.holds(Owner.GAMEPAD)

    /** Keys held and axis values right now (as last seen by us). */
    fun controllerSnapshot(): Pair<Set<Int>, IntArray> = pad.snapshot()

    /** The controller is present (its input node was found). */
    val hasController: Boolean get() = pad.node != null

    /** Whether a controller key is held right now (as last seen by us). */
    fun isDown(code: Int): Boolean = pad.isDown(code)

    private enum class Owner { NONE, AYN, RECENTS, GAMEPAD }

    fun start() {
        logi("input: home remapped=$homeRemapped")
        thread(name = "ayn", isDaemon = true) { aynLoop() }
        thread(name = "pad", isDaemon = true) { pad.loop() }
    }

    private fun post(block: () -> Unit) {
        worker.execute {
            try {
                block()
            } catch (e: Throwable) {
                logw("input handler failed", e)
            }
        }
    }

    // ---- AYN button -------------------------------------------------------------------------------

    private fun aynLoop() {
        while (true) {
            val node = findNode { it.name == "gpio-keys" && it.hasKey(KEY_F24) }
                ?: findNode { it.hasKey(KEY_F24) && !it.name.contains("Controller", true) }
            if (node == null) {
                logw("AYN button input node not found, retrying")
                Thread.sleep(5000)
                continue
            }
            logi("watching AYN button on $node")
            try {
                FileInputStream(node).use { s ->
                    // The lid's hall sensor is a switch on the same node.
                    lidClosedNow(s.fd)?.let { setLidClosed(it) }
                    readEvents(s) { type, code, value ->
                        if (type == EV_KEY && code == KEY_F24) onAyn(value)
                        if (type == EV_SW && code == SW_LID) setLidClosed(value != 0)
                    }
                }
            } catch (e: Exception) {
                logw("AYN read failed", e)
            }
            Thread.sleep(1000)
        }
    }

    private fun onAyn(value: Int) {
        when (value) {
            1 -> if (!aynDown) {
                aynDown = true
                comboUsed = false
                // Already grabbed for the recents screen: AYN then only closes it (on release).
                // Grabbed for gamepad mode: that grab's events go to combo detection meanwhile.
                if (!pad.beginGrab(Owner.AYN, pad::detect, MAX_GRAB_MS)) pad.armCombos()
            }
            0 -> if (aynDown) {
                aynDown = false
                pad.endGrab(Owner.AYN)
                if (!comboUsed) post { listener.onAynTap() }
            }
        }
    }

    private fun combo(c: Combo) {
        comboUsed = true
        post { listener.onCombo(c) }
    }

    // ---- Power button: ignored with the lid closed, handled by gamepad mode -------------------
    // The power button is a PMIC key that wakes the SoC from suspend. With the lid closed we hold
    // an exclusive grab on it, so Android never sees the press and the screens stay off; the
    // device drops back to sleep by itself. Gamepad mode takes it over the same way: Android must
    // not go to sleep then (the controller would lose power), so presses go to its handler. The
    // PMIC's long-press hardware reset still works.

    private val lidLock = Any()
    private var powerGrab: FileInputStream? = null

    @Volatile
    var lidClosed = false
        private set

    @Volatile
    private var powerHandler: ((down: Boolean) -> Unit)? = null

    /** Power button presses (down / up) go to [handler] instead of Android; null gives them back. */
    fun setPowerKeyHandler(handler: ((down: Boolean) -> Unit)?) {
        synchronized(lidLock) {
            powerHandler = handler
            updatePowerGrab()
        }
    }

    /** Current lid switch state through EVIOCGSW, or null if it can't be read. */
    private fun lidClosedNow(fd: FileDescriptor): Boolean? = try {
        ((IOCTL_INT.invoke(null, fd, EVIOCGSW_4) as Int) and (1 shl SW_LID)) != 0
    } catch (e: Throwable) {
        logw("reading the lid switch failed", e)
        null
    }

    private fun setLidClosed(closed: Boolean) {
        synchronized(lidLock) {
            if (closed != lidClosed) logi(if (closed) "lid closed: power button ignored" else "lid open")
            lidClosed = closed
            updatePowerGrab()
        }
    }

    private fun updatePowerGrab() {
        val want = lidClosed || powerHandler != null
        if (want == (powerGrab != null)) return
        if (!want) {
            try {
                powerGrab?.close()
            } catch (_: Exception) {
            }
            powerGrab = null
            logi("power button back to normal")
            return
        }
        val node = findNode { it.name == POWER_KEY_NAME } ?: findNode { it.name.contains("pwrkey") && it.hasKey(KEY_POWER) }
        if (node == null) {
            logw("power button input node not found")
            return
        }
        val s = try {
            FileInputStream(node).also { IOCTL_INT.invoke(null, it.fd, EVIOCGRAB) }
        } catch (e: Throwable) {
            logw("grabbing the power button failed", e)
            return
        }
        powerGrab = s
        logi("power button grabbed ($node)")
        thread(name = "pwrkey", isDaemon = true) {
            try {
                readEvents(s) { t, c, v ->
                    if (t != EV_KEY || c != KEY_POWER || v == 2) return@readEvents
                    when {
                        lidClosed -> if (v == 1) logi("power button pressed with the lid closed: ignored")
                        else -> powerHandler?.let { h -> post { h(v == 1) } }
                    }
                }
            } catch (_: Exception) {
                // closed when neither needs it any more
            }
        }
    }

    // ---- Controller -------------------------------------------------------------------------------

    private inner class Pad {
        @Volatile
        var node: String? = null
        private val lock = Any()
        private val keys = HashSet<Int>()
        private val abs = IntArray(ABS_CNT)

        private var grab: FileInputStream? = null
        private var owner = Owner.NONE
        private var grabThread: Thread? = null
        private var grabTimeout: ScheduledFuture<*>? = null
        private var startKeys: Set<Int> = emptySet()
        private var startAbs = IntArray(ABS_CNT)
        private var hatX = 0
        private var hatY = 0

        private val homeLock = Any()
        private var homeDown = false
        private var homeLongFired = false
        private var homeLong: ScheduledFuture<*>? = null

        fun loop() {
            while (true) {
                val n = findNode { it.name == CONTROLLER_NAME }
                if (n == null) {
                    Thread.sleep(3000)
                    continue
                }
                node = n
                logi("watching controller on $n")
                try {
                    FileInputStream(n).use { s ->
                        readEvents(s) { t, c, v ->
                            track(t, c, v)
                            if (t == EV_KEY && c == KEY_HOME) onHome(v)
                            if (t == EV_KEY && v == 1 && (c == BTN_TL || c == BTN_TR)) post { listener.onShoulder() }
                        }
                    }
                } catch (e: Exception) {
                    logw("controller read failed", e)
                }
                Thread.sleep(1000)
            }
        }

        private fun track(t: Int, c: Int, v: Int) {
            synchronized(lock) {
                when (t) {
                    EV_KEY -> if (v != 0) keys += c else keys -= c
                    EV_ABS -> if (c in 0 until ABS_CNT) abs[c] = v
                }
            }
        }

        fun isDown(code: Int): Boolean = synchronized(lock) { code in keys }

        fun snapshot(): Pair<Set<Int>, IntArray> = synchronized(lock) { HashSet(keys) to abs.copyOf() }

        fun holds(who: Owner): Boolean = synchronized(lock) { owner == who && grabThread?.isAlive == true }

        /** Combo detection starts from what is held now: nothing already down counts as a press. */
        fun armCombos() {
            synchronized(lock) {
                hatX = abs[ABS_HAT0X]
                hatY = abs[ABS_HAT0Y]
                trigKey[0] = BTN_TL2 in keys
                trigKey[1] = BTN_TR2 in keys
                trigAxis[0] = abs[ABS_Z] >= TRIGGER_ON
                trigAxis[1] = abs[ABS_RZ] >= TRIGGER_ON
                trigDown[0] = trigKey[0] || trigAxis[0]
                trigDown[1] = trigKey[1] || trigAxis[1]
            }
        }

        /** Grabs the controller exclusively for [who]; false if it is already grabbed or absent. */
        fun beginGrab(who: Owner, handler: (Int, Int, Int) -> Unit, timeoutMs: Long): Boolean {
            val n = node ?: return false
            synchronized(lock) { if (grab != null) return false }
            val s = try {
                FileInputStream(n)
            } catch (e: Exception) {
                return false
            }
            try {
                IOCTL_INT.invoke(null, s.fd, EVIOCGRAB)
            } catch (e: Throwable) {
                logw("EVIOCGRAB failed; combos will also reach apps", e)
                s.close()
                return false
            }
            synchronized(lock) {
                grab = s
                owner = who
                startKeys = HashSet(keys)
                startAbs = abs.copyOf()
                hatX = startAbs[ABS_HAT0X]
                hatY = startAbs[ABS_HAT0Y]
                // A trigger already held when AYN goes down doesn't count as a press.
                trigKey[0] = BTN_TL2 in startKeys
                trigKey[1] = BTN_TR2 in startKeys
                trigAxis[0] = startAbs[ABS_Z] >= TRIGGER_ON
                trigAxis[1] = startAbs[ABS_RZ] >= TRIGGER_ON
                trigDown[0] = trigKey[0] || trigAxis[0]
                trigDown[1] = trigKey[1] || trigAxis[1]
            }
            grabThread = thread(name = "pad-grab", isDaemon = true) {
                try {
                    readEvents(s) { t, c, v ->
                        track(t, c, v)
                        handler(t, c, v)
                    }
                } catch (_: Exception) {
                    // closed by endGrab()
                }
            }
            if (timeoutMs > 0) {
                grabTimeout = worker.schedule({
                    logw("$who held the controller for ${timeoutMs}ms, releasing it")
                    if (who == Owner.AYN) comboUsed = true
                    endGrab(who)
                }, timeoutMs, TimeUnit.MILLISECONDS)
            }
            return true
        }

        // L2 / R2 are analog (ABS_Z / ABS_RZ, 0..255) and also report a button when fully
        // pulled; either one counts, and a pull fires once until the trigger is let go again.
        private val trigKey = BooleanArray(2)
        private val trigAxis = BooleanArray(2)
        private val trigDown = BooleanArray(2)

        private fun trigger(i: Int, key: Boolean? = null, axis: Int? = null) {
            if (key != null) trigKey[i] = key
            if (axis != null) trigAxis[i] = if (trigAxis[i]) axis >= TRIGGER_OFF else axis >= TRIGGER_ON
            val down = trigKey[i] || trigAxis[i]
            if (down && !trigDown[i]) combo(if (i == 0) Combo.L2 else Combo.R2)
            trigDown[i] = down
        }

        fun detect(t: Int, c: Int, v: Int) {
            when (t) {
                EV_KEY -> when (c) {
                    BTN_TL2 -> trigger(0, key = v != 0)
                    BTN_TR2 -> trigger(1, key = v != 0)
                }
                EV_ABS -> when (c) {
                    ABS_Z -> trigger(0, axis = v)
                    ABS_RZ -> trigger(1, axis = v)
                }
            }
            when (t) {
                EV_KEY -> if (v == 1) when (c) {
                    BTN_TL -> combo(Combo.L1)
                    BTN_TR -> combo(Combo.R1)
                    BTN_EAST -> combo(Combo.B)
                    BTN_NORTH -> combo(Combo.X)
                    BTN_WEST -> combo(Combo.Y)
                    BTN_DPAD_UP, KEY_UP -> combo(Combo.UP)
                    BTN_DPAD_DOWN, KEY_DOWN -> combo(Combo.DOWN)
                    BTN_DPAD_LEFT, KEY_LEFT -> combo(Combo.LEFT)
                    BTN_DPAD_RIGHT, KEY_RIGHT -> combo(Combo.RIGHT)
                }
                EV_ABS -> when (c) {
                    ABS_HAT0Y -> {
                        if (v < 0 && hatY >= 0) combo(Combo.UP)
                        if (v > 0 && hatY <= 0) combo(Combo.DOWN)
                        hatY = v
                    }
                    ABS_HAT0X -> {
                        if (v < 0 && hatX >= 0) combo(Combo.LEFT)
                        if (v > 0 && hatX <= 0) combo(Combo.RIGHT)
                        hatX = v
                    }
                }
            }
        }

        fun endGrab(who: Owner) {
            val s: FileInputStream
            val t: Thread?
            synchronized(lock) {
                if (owner != who) return
                s = grab ?: return
                grab = null
                owner = Owner.NONE
                t = grabThread
                grabThread = null
            }
            grabTimeout?.cancel(false)
            try {
                s.close() // wakes the blocked reader; the grab is released with the last file reference
            } catch (_: Exception) {
            }
            t?.join(300)
            resync()
            // A Home release that went to the grab never reaches our own reader (and the kernel
            // drops a re-injected one, since the key is already up): reset the Home state here.
            synchronized(homeLock) {
                if (homeDown && !isDown(KEY_HOME)) {
                    homeDown = false
                    homeLong?.cancel(false)
                    homeLong = null
                }
            }
        }

        /**
         * Android saw nothing while we held the grab. Release anything it still believes is
         * pressed and bring analog axes up to date, but never replay a press that started during
         * the grab (that would send the combo button to the game after all).
         */
        private fun resync() {
            val events = ArrayList<IntArray>()
            synchronized(lock) {
                for (c in startKeys) if (c !in keys) events += intArrayOf(EV_KEY, c, 0)
                for (c in ANALOG_AXES) if (abs[c] != startAbs[c]) events += intArrayOf(EV_ABS, c, abs[c])
                for (c in intArrayOf(ABS_HAT0X, ABS_HAT0Y)) if (startAbs[c] != 0 && abs[c] == 0) events += intArrayOf(EV_ABS, c, 0)
            }
            val n = node ?: return
            if (events.isEmpty()) return
            try {
                val bb = ByteBuffer.allocate(EVENT_SIZE * (events.size + 1)).order(ByteOrder.LITTLE_ENDIAN)
                for (e in events) putEvent(bb, e[0], e[1], e[2])
                putEvent(bb, EV_SYN, 0, 0)
                FileOutputStream(n).use { it.write(bb.array()) }
            } catch (e: Exception) {
                logw("controller resync failed", e)
            }
        }

        /** Short press = Home on release (like stock), long press = app switcher while still held. */
        private fun onHome(v: Int) {
            synchronized(homeLock) {
                when (v) {
                    1 -> {
                        if (homeDown) return
                        homeDown = true
                        homeLongFired = false
                        if (homeRemapped) post { listener.onHomeDown() }
                        homeLong = worker.schedule({
                            val fire = synchronized(homeLock) {
                                if (homeDown) homeLongFired = true
                                homeDown
                            }
                            if (fire) listener.onHomeLong()
                        }, LONG_HOME_MS, TimeUnit.MILLISECONDS)
                    }
                    0 -> {
                        if (!homeDown) return
                        homeDown = false
                        homeLong?.cancel(false)
                        homeLong = null
                        // Without the key layout remap Android already handled the short press.
                        if (!homeLongFired && homeRemapped) post { listener.onHomeSingle() }
                    }
                }
            }
        }
    }

    // ---- evdev helpers ----------------------------------------------------------------------------

    private class DevInfo(val name: String, val handler: String, private val keyBits: List<Long>) {
        fun hasKey(code: Int): Boolean {
            val idx = code / 64
            return idx < keyBits.size && (keyBits[idx] shr (code % 64)) and 1L == 1L
        }
    }

    private fun devices(): List<DevInfo> {
        val text = Sys.read("/proc/bus/input/devices") ?: return emptyList()
        return text.split(Regex("\n\\s*\n")).mapNotNull { block ->
            val name = Regex("N: Name=\"([^\"]*)\"").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val handler = Regex("H: Handlers=.*?(event\\d+)").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val bits = Regex("B: KEY=([0-9a-f ]+)").find(block)?.groupValues?.get(1)
                ?.trim()?.split(' ')?.reversed()?.map { java.lang.Long.parseUnsignedLong(it, 16) }
                ?: emptyList()
            DevInfo(name, handler, bits)
        }
    }

    private fun findNode(match: (DevInfo) -> Boolean): String? =
        devices().firstOrNull(match)?.let { "/dev/input/${it.handler}" }

    private inline fun readEvents(input: InputStream, cb: (Int, Int, Int) -> Unit) {
        val buf = ByteArray(EVENT_SIZE * 64)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) return
            var off = 0
            while (off + EVENT_SIZE <= n) {
                cb(bb.getShort(off + 16).toInt() and 0xFFFF, bb.getShort(off + 18).toInt() and 0xFFFF, bb.getInt(off + 20))
                off += EVENT_SIZE
            }
        }
    }

    private fun putEvent(bb: ByteBuffer, type: Int, code: Int, value: Int) {
        bb.putLong(0L).putLong(0L).putShort(type.toShort()).putShort(code.toShort()).putInt(value)
    }

    companion object {
        const val EVENT_SIZE = 24 // struct input_event on a 64-bit kernel
        const val EV_SYN = 0
        const val EV_KEY = 1
        const val EV_ABS = 3
        const val EV_SW = 5
        const val SW_LID = 0
        const val KEY_POWER = 116
        const val KEY_BACK = 158
        const val BTN_SOUTH = 304 // "A"
        const val BTN_THUMBR = 318
        const val KEY_F24 = 194
        const val KEY_HOME = 102
        const val KEY_UP = 103
        const val KEY_LEFT = 105
        const val KEY_RIGHT = 106
        const val KEY_DOWN = 108
        const val BTN_TL = 310 // L1
        const val BTN_TR = 311 // R1
        const val BTN_TL2 = 312 // L2 (digital)
        const val BTN_TR2 = 313 // R2 (digital)
        const val ABS_Z = 0x02 // L2 (analog, 0..255)
        const val ABS_RZ = 0x05 // R2 (analog, 0..255)
        const val TRIGGER_ON = 180
        const val TRIGGER_OFF = 110
        const val BTN_EAST = 305 // "B" in the controller key layout
        const val BTN_NORTH = 307 // "X"
        const val BTN_WEST = 308 // "Y"
        const val BTN_DPAD_UP = 544
        const val BTN_DPAD_DOWN = 545
        const val BTN_DPAD_LEFT = 546
        const val BTN_DPAD_RIGHT = 547
        const val ABS_HAT0X = 0x10
        const val ABS_HAT0Y = 0x11
        const val ABS_CNT = 0x40
        val ANALOG_AXES = intArrayOf(0, 1, 2, 3, 4, 5, 9, 10)

        /** _IOW('E', 0x90, int) */
        const val EVIOCGRAB = 0x40044590

        /** _IOC(_IOC_READ, 'E', 0x1b, 4): the first 32 switch bits. */
        const val EVIOCGSW_4 = 0x8004451B.toInt()
        const val POWER_KEY_NAME = "pmic_pwrkey"
        const val MAX_GRAB_MS = 8000L
        const val LONG_HOME_MS = 450L

        const val CONTROLLER_NAME = "Xbox Wireless Controller"
        const val CONTROLLER_KL = "/vendor/usr/keylayout/Vendor_045e_Product_028e_Version_1234.kl"

        /** android.system.Os.ioctlInt(fd, cmd) passes a non-null int*, which EVIOCGRAB treats as "grab". */
        private val IOCTL_INT = android.system.Os::class.java
            .getMethod("ioctlInt", FileDescriptor::class.java, Int::class.javaPrimitiveType)
    }
}
