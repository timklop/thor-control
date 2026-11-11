package com.thorctl.daemon

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.thorctl.shared.Proto
import kotlin.concurrent.thread

/**
 * Debug client: sends each argument as a JSON command and prints replies for a while.
 *
 *   CLASSPATH=<apk> app_process /system/bin com.thorctl.daemon.Cli '{"cmd":"get_state"}'
 */
object Cli {
    @JvmStatic
    fun main(args: Array<String>) {
        val waitMs = System.getenv("WAIT_MS")?.toLongOrNull() ?: 1500L
        val s = LocalSocket()
        s.connect(LocalSocketAddress(Proto.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
        thread(isDaemon = true) {
            try {
                s.inputStream.bufferedReader().forEachLine { println(it) }
            } catch (_: Exception) {
            }
        }
        for (a in args) {
            s.outputStream.write((a + "\n").toByteArray())
            s.outputStream.flush()
            Thread.sleep(150)
        }
        Thread.sleep(waitMs)
        s.close()
    }
}
