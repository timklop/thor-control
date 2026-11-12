package com.thorctl.daemon

import android.os.IBinder
import android.view.InputDevice
import java.lang.reflect.Method

/**
 * Reflection wrappers around hidden system service binder interfaces. The daemon runs as
 * uid 0 under app_process, so permission checks (CONTROL_DISPLAY_BRIGHTNESS,
 * ASSOCIATE_INPUT_DEVICE_TO_DISPLAY, ...) pass and hidden API restrictions do not apply.
 *
 * Signatures verified against the Android 17 (SDK 37) framework on the device.
 */
internal object SystemServices {
    private class Svc(val name: String, val iface: String) {
        private var binder: IBinder? = null
        private var proxy: Any? = null
        private val methods = HashMap<String, Method>()

        @Synchronized
        fun get(): Any? {
            val b = binder
            if (b != null && b.isBinderAlive && proxy != null) return proxy
            val nb = getService(name) ?: return null
            binder = nb
            methods.clear()
            proxy = Class.forName("$iface\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, nb)
            return proxy
        }

        @Synchronized
        fun call(method: String, vararg args: Any?): Any? {
            val p = get() ?: throw IllegalStateException("service $name not available")
            val m = methods.getOrPut(method + args.size) {
                p.javaClass.methods.first { it.name == method && it.parameterTypes.size == args.size }
            }
            return m.invoke(p, *args)
        }
    }

    private fun getService(name: String): IBinder? = try {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, name) as IBinder?
    } catch (_: Throwable) {
        null
    }

    private val display = Svc("display", "android.hardware.display.IDisplayManager")
    private val input = Svc("input", "android.hardware.input.IInputManager")
    private val power = Svc("power", "android.os.IPowerManager")
    private val atm = Svc("activity_task", "android.app.IActivityTaskManager")
    private val am = Svc("activity", "android.app.IActivityManager")
    private val pm = Svc("package", "android.content.pm.IPackageManager")

    fun servicesReady(): Boolean =
        getService("display") != null && getService("input") != null &&
            getService("power") != null && getService("activity_task") != null

    // ---- Displays -------------------------------------------------------------------------

    data class DisplayDesc(val id: Int, val uniqueId: String, val type: Int, val width: Int, val height: Int, val state: Int)

    fun displays(): List<DisplayDesc> {
        val ids = display.call("getDisplayIds", false) as IntArray
        return ids.toList().mapNotNull { id ->
            val info = display.call("getDisplayInfo", id) ?: return@mapNotNull null
            val c = info.javaClass
            DisplayDesc(
                id = id,
                uniqueId = c.getField("uniqueId").get(info) as? String ?: "",
                type = c.getField("type").getInt(info),
                width = c.getField("logicalWidth").getInt(info),
                height = c.getField("logicalHeight").getInt(info),
                state = c.getField("state").getInt(info),
            )
        }
    }

    fun getBrightness(displayId: Int): Float = display.call("getBrightness", displayId) as Float

    fun setBrightness(displayId: Int, value: Float) {
        display.call("setBrightness", displayId, value)
    }

    /** state: Display.STATE_OFF (1) to force off, Display.STATE_UNKNOWN (0) to hand control back. */
    fun requestDisplayPower(displayId: Int, state: Int): Boolean =
        display.call("requestDisplayPower", displayId, state) as Boolean

    // ---- Input ----------------------------------------------------------------------------

    fun inputDevices(): List<InputDevice> {
        val ids = input.call("getInputDeviceIds") as IntArray
        return ids.toList().mapNotNull { input.call("getInputDevice", it) as InputDevice? }
    }

    fun associateByDescriptor(descriptor: String, displayUniqueId: String) {
        input.call("addUniqueIdAssociationByDescriptor", descriptor, displayUniqueId)
    }

    fun removeAssociationByDescriptor(descriptor: String) {
        input.call("removeUniqueIdAssociationByDescriptor", descriptor)
    }

    fun setInputDeviceEnabled(deviceId: Int, enabled: Boolean) {
        input.call(if (enabled) "enableInputDevice" else "disableInputDevice", deviceId)
    }

    // ---- Power / tasks --------------------------------------------------------------------

    fun isInteractive(): Boolean = try {
        power.call("isInteractive") as Boolean
    } catch (_: Throwable) {
        true
    }

    /**
     * User activity on [displayId]'s power group, as a touch would be: restarts its screen timeout
     * (and brightens it from dim). Each panel can be its own power group, so call it per display.
     */
    fun userActivity(displayId: Int) {
        power.call("userActivity", displayId, android.os.SystemClock.uptimeMillis(), 0 /* USER_ACTIVITY_EVENT_OTHER */, 0)
    }

    /** Display of the focused task, or -1. */
    fun focusedDisplayId(): Int = try {
        atm.call("getFocusedRootTaskInfo")?.let { field(it, "displayId") as? Int } ?: -1
    } catch (_: Throwable) {
        -1
    }

    fun focusTopTask(displayId: Int) {
        atm.call("focusTopTask", displayId)
    }

    data class TaskDesc(val taskId: Int, val pkg: String?)

    /** Top-most visible standard (non-home) root task on a display, i.e. the app the user sees. */
    fun topAppTask(displayId: Int): TaskDesc? {
        val list = atm.call("getAllRootTaskInfosOnDisplay", displayId) as? List<*> ?: return null
        for (info in list) {
            if (info == null) continue
            val type = field(info, "topActivityType") as? Int ?: continue
            val visible = (field(info, "visible") as? Boolean ?: false) || (field(info, "isVisible") as? Boolean ?: false)
            if (type == ACTIVITY_TYPE_STANDARD && visible) {
                val top = field(info, "topActivity") as? android.content.ComponentName
                    ?: field(info, "baseActivity") as? android.content.ComponentName
                return TaskDesc(field(info, "taskId") as Int, top?.packageName)
            }
        }
        return null
    }

    fun moveTaskToDisplay(taskId: Int, displayId: Int) {
        atm.call("moveRootTaskToDisplay", taskId, displayId)
        // A raw reparent skips the "task to front" transition that Launcher tracks; without it
        // Launcher believes it is still visible and the next app-switch toggles the wrong state.
        try {
            val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
            atm.call("startActivityFromRecents", taskId, opts)
        } catch (e: Throwable) {
            logw("bring task $taskId to front failed", e)
        }
    }

    /** Injects a key press (down + up) as the system would, optionally targeted at one display. */
    fun injectKey(keyCode: Int, displayId: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        for (action in intArrayOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
            val ev = android.view.KeyEvent(
                now, now, action, keyCode, 0, 0,
                android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                android.view.KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD,
            )
            if (displayId >= 0) {
                android.view.KeyEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(ev, displayId)
            }
            input.call("injectInputEvent", ev, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */)
        }
    }

    /** Packages of every visible root task on a display (any activity type, home included). */
    fun visiblePackages(displayId: Int): Set<String> {
        val list = atm.call("getAllRootTaskInfosOnDisplay", displayId) as? List<*> ?: return emptySet()
        val out = HashSet<String>()
        for (info in list) {
            if (info == null) continue
            val visible = (field(info, "visible") as? Boolean ?: false) || (field(info, "isVisible") as? Boolean ?: false)
            if (!visible) continue
            ((field(info, "topActivity") ?: field(info, "baseActivity")) as? android.content.ComponentName)?.let { out += it.packageName }
        }
        return out
    }

    // ---- Recent tasks ---------------------------------------------------------------------

    /** displayId is -1 for a task that is no longer running (it is restored when opened). */
    data class RecentTask(val taskId: Int, val pkg: String, val displayId: Int, val visible: Boolean, val running: Boolean)

    /** Most recent first, as the system's recents list (home and excluded tasks are left out). */
    fun recentTasks(max: Int): List<RecentTask> {
        val slice = atm.call("getRecentTasks", max, RECENT_IGNORE_UNAVAILABLE, 0) ?: return emptyList()
        val list = slice.javaClass.getMethod("getList").invoke(slice) as? List<*> ?: return emptyList()
        return list.mapNotNull { info ->
            if (info == null) return@mapNotNull null
            val base = (field(info, "baseIntent") as? android.content.Intent)?.component
            val pkg = ((field(info, "topActivity") ?: field(info, "baseActivity")) as? android.content.ComponentName)?.packageName
                ?: base?.packageName ?: return@mapNotNull null
            RecentTask(
                taskId = field(info, "taskId") as? Int ?: return@mapNotNull null,
                pkg = pkg,
                displayId = field(info, "displayId") as? Int ?: -1,
                visible = field(info, "isVisible") as? Boolean ?: false,
                running = field(info, "isRunning") as? Boolean ?: false,
            )
        }
    }

    /** Brings a recent task to front on [displayId], restoring it if it is no longer running. */
    fun startFromRecents(taskId: Int, displayId: Int) {
        val opts = android.app.ActivityOptions.makeBasic()
        if (displayId >= 0) opts.launchDisplayId = displayId
        atm.call("startActivityFromRecents", taskId, opts.toBundle())
    }

    /** Removes the task from recents and closes it, like swiping it away. */
    fun removeTask(taskId: Int): Boolean = atm.call("removeTask", taskId) as? Boolean ?: false

    /**
     * Asks the system for a fresh preview of a visible task. With updateCache it is also written
     * to /data/system_ce/0/snapshots/ within ~150 ms, where [snapshotFile] finds it. The returned
     * TaskSnapshot is dropped right away (its graphics buffer is released).
     */
    fun takeSnapshot(taskId: Int) {
        val tsm = atm.call("getTaskSnapshotManager") ?: return
        val m = tsm.javaClass.methods.firstOrNull { it.name == "takeTaskSnapshot" } ?: return
        val args: Array<Any?> = when (m.parameterTypes.size) {
            4 -> arrayOf(taskId, true, false, false)
            3 -> arrayOf(taskId, true, false)
            2 -> arrayOf(taskId, true)
            else -> return
        }
        val snap = m.invoke(tsm, *args) ?: return
        try {
            (snap.javaClass.getMethod("getHardwareBuffer").invoke(snap) as? AutoCloseable)?.close()
        } catch (_: Throwable) {
        }
    }

    /** The persisted low-resolution preview of a task, if the system has one. */
    fun snapshotFile(taskId: Int): java.io.File? {
        val root = java.io.File("/data/system_ce/0/snapshots")
        val dirs = listOf(root) + (root.listFiles()?.filter { it.isDirectory } ?: emptyList())
        for (name in listOf("${taskId}_reduced.jpg", "$taskId.jpg")) {
            dirs.map { java.io.File(it, name) }.filter { it.isFile }.maxByOrNull { it.lastModified() }?.let { return it }
        }
        return null
    }

    // ---- Processes / packages -------------------------------------------------------------

    data class AppProcess(val pid: Int, val uid: Int, val name: String, val pkgs: List<String>, val importance: Int)

    fun runningApps(): List<AppProcess> {
        val list = am.call("getRunningAppProcesses") as? List<*> ?: return emptyList()
        return list.mapNotNull { p ->
            val i = p as? android.app.ActivityManager.RunningAppProcessInfo ?: return@mapNotNull null
            AppProcess(i.pid, i.uid, i.processName ?: "", i.pkgList?.toList() ?: emptyList(), i.importance)
        }
    }

    fun forceStop(pkg: String) {
        am.call("forceStopPackage", pkg, 0)
    }

    /** Kills the package's processes that are in the background (adj >= service); visible ones survive. */
    fun killBackground(pkg: String) {
        am.call("killBackgroundProcesses", pkg, 0)
    }

    /** ApplicationInfo.flags, or null if the package is unknown. */
    fun appFlags(pkg: String): Int? {
        val p = pm.get() ?: return null
        val m = p.javaClass.methods.firstOrNull { it.name == "getApplicationInfo" && it.parameterTypes.size == 3 } ?: return null
        val flagsArg: Any = if (m.parameterTypes[1] == Long::class.javaPrimitiveType) 0L else 0
        val info = m.invoke(p, pkg, flagsArg, 0) as? android.content.pm.ApplicationInfo ?: return null
        return info.flags
    }

    fun homePackage(): String? = try {
        (pm.call("getHomeActivities", ArrayList<android.content.pm.ResolveInfo>()) as? android.content.ComponentName)?.packageName
    } catch (e: Throwable) {
        logw("getHomeActivities failed", e)
        null
    }

    private fun field(obj: Any, name: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }.get(obj)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    private const val ACTIVITY_TYPE_STANDARD = 1
    private const val RECENT_IGNORE_UNAVAILABLE = 0x0002
}
