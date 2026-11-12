package com.thorctl.app

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.thorctl.shared.DaemonState
import com.thorctl.shared.FanProfile
import com.thorctl.shared.GameOptions
import com.thorctl.shared.LedState
import com.thorctl.shared.Proto
import com.thorctl.shared.Stats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Keeps a connection to the root daemon and exposes its state as flows. */
object DaemonClient {
    private const val TAG = "ThorClient"

    private val _state = MutableStateFlow<DaemonState?>(null)
    val state: StateFlow<DaemonState?> = _state

    private val _stats = MutableStateFlow<Stats?>(null)
    val stats: StateFlow<Stats?> = _stats

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "thor-client-tx") }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var out: OutputStream? = null

    @Volatile
    private var socket: LocalSocket? = null

    @Volatile
    private var statsWanted = 0

    var onButton: ((String) -> Unit)? = null
    var onHud: ((JSONObject) -> Unit)? = null

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        thread(name = "thor-client-rx", isDaemon = true) {
            while (true) {
                try {
                    val s = LocalSocket()
                    s.connect(LocalSocketAddress(Proto.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
                    socket = s
                    out = s.outputStream
                    _connected.value = true
                    Log.i(TAG, "connected to daemon")
                    sendNow(JSONObject().put("cmd", "hello"))
                    // A daemon (re)start doesn't know the keyboard is up: L1 / R1 are shift only then.
                    if (KeyboardController.isShown) sendNow(JSONObject().put("cmd", "kb_visible").put("on", true))
                    if (statsWanted > 0) sendNow(JSONObject().put("cmd", "stats").put("on", true))
                    s.inputStream.bufferedReader().forEachLine(::onLine)
                } catch (e: IOException) {
                    // daemon not up yet (boot) or restarting
                } catch (e: Exception) {
                    Log.w(TAG, "client loop", e)
                }
                _connected.value = false
                out = null
                try {
                    socket?.close()
                } catch (_: IOException) {
                }
                socket = null
                Thread.sleep(1000)
            }
        }
    }

    private fun onLine(line: String) {
        try {
            val o = JSONObject(line)
            when (o.optString("ev")) {
                "state" -> _state.value = DaemonState.fromJson(o)
                "stats" -> _stats.value = Stats.fromJson(o)
                "button" -> {
                    val type = o.optString("type")
                    main.post { onButton?.invoke(type) }
                }
                "hud" -> main.post { onHud?.invoke(o) }
                "haptic" -> main.post { OverlayController.haptic() }
                "recents", "recents_thumb", "recents_key", "recents_close", "home_anim" -> main.post { RecentsController.onEvent(o) }
                "kb_key" -> main.post { KeyboardController.onControllerKey(o.optString("key")) }
                // Gamepad mode over Bluetooth: straight to the radio, off the main thread.
                "gp_report" -> BluetoothGamepad.send(o.optString("r"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad line: $line", e)
        }
    }

    private fun sendNow(o: JSONObject) {
        val os = out ?: return
        try {
            os.write((o.toString() + "\n").toByteArray())
            os.flush()
        } catch (e: IOException) {
            try {
                socket?.close()
            } catch (_: IOException) {
            }
        }
    }

    fun send(o: JSONObject) {
        writer.execute { sendNow(o) }
    }

    /** Reference counted: the overlay and the activity can both want live stats. */
    fun subscribeStats(on: Boolean) {
        statsWanted = (statsWanted + if (on) 1 else -1).coerceAtLeast(0)
        send(JSONObject().put("cmd", "stats").put("on", statsWanted > 0))
    }

    // ---- commands -----------------------------------------------------------------------------

    fun setFanProfile(id: String) = send(JSONObject().put("cmd", "fan_profile").put("id", id))

    fun saveFanProfile(p: FanProfile, activate: Boolean = false) =
        send(JSONObject().put("cmd", "fan_save").put("profile", p.toJson()).put("activate", activate))

    fun deleteFanProfile(id: String) = send(JSONObject().put("cmd", "fan_delete").put("id", id))

    fun setBrightness(display: String, value: Float, final: Boolean) =
        send(JSONObject().put("cmd", "brightness").put("display", display).put("value", value.toDouble()).put("final", final))

    fun setController(target: String) = send(JSONObject().put("cmd", "controller").put("target", target))

    fun setLeds(s: LedState) {
        _state.value?.let { _state.value = it.copy(leds = s) } // optimistic, keeps sliders smooth
        send(JSONObject().put("cmd", "leds").put("state", s.toJson()))
    }

    fun setBottomPower(on: Boolean) = send(JSONObject().put("cmd", "bottom_power").put("on", on))

    fun setGameMode(on: Boolean) {
        _state.value?.let { _state.value = it.copy(game = it.game.copy(enabled = on)) }
        send(JSONObject().put("cmd", "game_mode").put("on", on))
    }

    fun setGameOptions(o: GameOptions) {
        _state.value?.let { _state.value = it.copy(game = it.game.copy(options = o)) }
        send(JSONObject().put("cmd", "game_options").put("options", o.toJson()))
    }

    fun recentsOpen(taskId: Int) = send(JSONObject().put("cmd", "recents_open").put("id", taskId))

    fun recentsRemove(taskId: Int) = send(JSONObject().put("cmd", "recents_remove").put("id", taskId))

    fun recentsClear(taskIds: List<Int>) =
        send(JSONObject().put("cmd", "recents_clear").put("ids", org.json.JSONArray(taskIds)))

    fun recentsClose() = send(JSONObject().put("cmd", "recents_close"))

    fun recentsHome() = send(JSONObject().put("cmd", "recents_home"))

    fun homeGo(seq: Int) = send(JSONObject().put("cmd", "home_go").put("seq", seq))

    fun overlayDone() = send(JSONObject().put("cmd", "overlay_done"))

    fun keyboardVisible(on: Boolean) = send(JSONObject().put("cmd", "kb_visible").put("on", on))

    fun setGamepad(on: Boolean) = send(JSONObject().put("cmd", "gamepad").put("on", on))

    fun setGamepadTransport(t: String) = send(JSONObject().put("cmd", "gamepad_transport").put("transport", t))

    fun setGamepadScreensOff(on: Boolean) = send(JSONObject().put("cmd", "gamepad_screens_off").put("on", on))

    fun gamepadBluetooth(connected: Boolean, host: String) =
        send(JSONObject().put("cmd", "gamepad_bt").put("connected", connected).put("host", host))

    fun moveApp(toBottom: Boolean) =
        send(JSONObject().put("cmd", "move_app").put("to", if (toBottom) Proto.TARGET_BOTTOM else Proto.TARGET_TOP))
}
