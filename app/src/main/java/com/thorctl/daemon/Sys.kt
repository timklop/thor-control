package com.thorctl.daemon

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

internal const val TAG = "thorctld"

internal fun logi(msg: String) {
    Log.i(TAG, msg)
}

internal fun logw(msg: String, t: Throwable? = null) {
    Log.w(TAG, msg, t)
}

/** Small, allocation-light sysfs helpers. Everything here runs as root. */
internal object Sys {
    fun read(path: String): String? = try {
        File(path).readText().trim()
    } catch (_: Exception) {
        null
    }

    fun readInt(path: String): Int? = read(path)?.split(' ', '\n')?.firstOrNull()?.toIntOrNull()

    fun readLong(path: String): Long? = read(path)?.split(' ', '\n')?.firstOrNull()?.toLongOrNull()

    fun write(path: String, value: String): Boolean = try {
        FileOutputStream(path).use { it.write(value.toByteArray()) }
        true
    } catch (e: Exception) {
        logw("write $path <- '$value' failed: ${e.message}")
        false
    }

    private val getProp by lazy { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java) }

    /** System property value ("" if unset), without spawning getprop. */
    fun prop(name: String): String = try {
        getProp.invoke(null, name) as String
    } catch (_: Throwable) {
        ""
    }

    /** Runs a shell command as the daemon (root). Returns stdout, or null on failure/timeout. */
    fun sh(cmd: String, timeoutMs: Long = 5000): String? = try {
        val p = ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            null
        } else {
            out.trim()
        }
    } catch (e: Exception) {
        logw("sh '$cmd' failed: ${e.message}")
        null
    }
}
