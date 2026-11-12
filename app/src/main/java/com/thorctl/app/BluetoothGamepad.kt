package com.thorctl.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Base64
import android.util.Log
import com.thorctl.shared.GamepadHid
import com.thorctl.shared.GamepadState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Gamepad mode over Bluetooth. While the daemon's gamepad mode is on with Bluetooth, the Thor is
 * registered as a Bluetooth HID device (a gamepad, with the same report as over USB) and the
 * daemon's reports are sent to the connected host. On start it reconnects to the last host, as
 * Bluetooth gamepads do; a new host pairs from its own Bluetooth settings while the Thor is
 * discoverable ([makeDiscoverable]).
 */
@SuppressLint("MissingPermission", "StaticFieldLeak")
object BluetoothGamepad {
    private const val TAG = "ThorGamepadBt"
    private const val PREF_HOST = "host"

    private lateinit var app: Context
    private val tx = Executors.newSingleThreadExecutor { Thread(it, "gamepad-bt") }

    private var wanted = false

    @Volatile
    private var proxy: BluetoothHidDevice? = null

    @Volatile
    private var host: BluetoothDevice? = null

    @Volatile
    private var lastReport = GamepadHid.neutral()

    /** Newest report not yet handed to the radio (newest wins, nothing queues up). */
    private val pending = AtomicReference<ByteArray?>(null)

    private val adapter: BluetoothAdapter?
        get() = app.getSystemService(BluetoothManager::class.java)?.adapter

    fun init(ctx: Context) {
        app = ctx.applicationContext
        MainScope().launch {
            DaemonClient.state.collect { s ->
                val g = s?.gamepad
                update(g != null && g.on && g.transport == GamepadState.BLUETOOTH)
            }
        }
        // Gamepad mode turns Bluetooth on if it was off: register once the adapter is up.
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                if (st == BluetoothAdapter.STATE_ON && wanted && proxy == null) open()
                if (st == BluetoothAdapter.STATE_OFF) {
                    proxy = null
                    setHost(null)
                }
            }
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    private fun update(want: Boolean) {
        if (want == wanted) return
        wanted = want
        if (want) open() else close()
    }

    private fun open() {
        val a = adapter ?: return
        if (!a.isEnabled) return // the daemon is switching Bluetooth on; ACTION_STATE_CHANGED follows
        try {
            a.getProfileProxy(app, listener, BluetoothProfile.HID_DEVICE)
        } catch (e: Exception) {
            Log.w(TAG, "no HID device profile", e)
        }
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
            val hid = p as BluetoothHidDevice
            if (!wanted) {
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
                return
            }
            proxy = hid
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "Thor Controller",
                "Gamepad",
                "Thor Control",
                (BluetoothHidDevice.SUBCLASS1_NONE.toInt() or BluetoothHidDevice.SUBCLASS2_GAMEPAD.toInt()).toByte(),
                GamepadHid.DESCRIPTOR,
            )
            try {
                if (!hid.registerApp(sdp, null, null, tx, callback)) Log.w(TAG, "registerApp refused")
            } catch (e: Exception) {
                Log.w(TAG, "registerApp failed", e)
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            proxy = null
            setHost(null)
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "registered=$registered plugged=${pluggedDevice?.address}")
            if (!registered) {
                setHost(null)
                return
            }
            // Gamepads reconnect to their host themselves.
            val target = pluggedDevice ?: lastHost() ?: return
            try {
                proxy?.connect(target)
            } catch (e: Exception) {
                Log.w(TAG, "reconnect failed", e)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.i(TAG, "host ${device.address} state=$state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    app.getSharedPreferences("gamepad", Context.MODE_PRIVATE).edit().putString(PREF_HOST, device.address).apply()
                    setHost(device)
                    // The host starts from the current controller state.
                    pending.set(lastReport)
                    flush()
                }
                BluetoothProfile.STATE_DISCONNECTED -> if (host == null || host?.address == device.address) setHost(null)
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            try {
                proxy?.replyReport(device, type, id, lastReport)
            } catch (_: Exception) {
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            try {
                proxy?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            } catch (_: Exception) {
            }
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            setHost(null)
        }
    }

    private fun lastHost(): BluetoothDevice? {
        val addr = app.getSharedPreferences("gamepad", Context.MODE_PRIVATE).getString(PREF_HOST, null) ?: return null
        val a = adapter ?: return null
        return a.bondedDevices?.firstOrNull { it.address == addr }
    }

    private fun setHost(d: BluetoothDevice?) {
        host = d
        val name = d?.let { it.alias ?: it.name ?: it.address } ?: ""
        DaemonClient.gamepadBluetooth(d != null, name)
    }

    private fun close() {
        val p = proxy
        proxy = null
        host = null
        if (p != null) {
            tx.execute {
                try {
                    p.unregisterApp()
                } catch (_: Exception) {
                }
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, p)
            }
        }
    }

    /** A report from the daemon (base64 payload, no report ID). */
    fun send(b64: String) {
        val r = try {
            Base64.decode(b64, Base64.NO_WRAP)
        } catch (_: Exception) {
            return
        }
        if (r.size != GamepadHid.PAYLOAD) return
        lastReport = r
        if (host == null) return
        // Only one hand-off in flight; a newer report replaces one still waiting.
        if (pending.getAndSet(r) == null) flush()
    }

    private fun flush() {
        tx.execute {
            val r = pending.getAndSet(null) ?: return@execute
            val p = proxy ?: return@execute
            val h = host ?: return@execute
            try {
                p.sendReport(h, GamepadHid.REPORT_ID, r)
            } catch (e: Exception) {
                Log.w(TAG, "sendReport failed", e)
            }
        }
    }

    /**
     * Lets a new host find the Thor: Android's "make visible" prompt (two minutes). The quick menu
     * closes first, since it covers the prompt.
     */
    fun makeDiscoverable() {
        OverlayController.hide()
        val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(i)
        } catch (e: Exception) {
            Log.w(TAG, "discoverable prompt failed", e)
        }
    }
}
