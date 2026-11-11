package com.thorctl.daemon

import android.net.LocalServerSocket
import android.net.LocalSocket
import com.thorctl.shared.Proto
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Line-delimited JSON over an abstract unix socket ("@thorctl"), peer uid checked. */
internal class SocketServer(
    private val isAllowed: (Int) -> Boolean,
    private val handler: (SocketServer.Client, JSONObject) -> Unit,
) {
    private val clients = CopyOnWriteArrayList<Client>()

    inner class Client(private val socket: LocalSocket, val uid: Int) : Thread("client-$uid") {
        private val out = socket.outputStream

        @Volatile
        var wantsStats = false

        fun send(line: String) {
            synchronized(this) {
                try {
                    out.write((line + "\n").toByteArray())
                    out.flush()
                } catch (e: IOException) {
                    close()
                }
            }
        }

        override fun run() {
            try {
                socket.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    try {
                        handler(this, JSONObject(line))
                    } catch (e: Exception) {
                        logw("bad command '$line'", e)
                    }
                }
            } catch (_: IOException) {
            } finally {
                close()
            }
        }

        fun close() {
            if (clients.remove(this)) logi("client uid=$uid disconnected")
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    val hasClients: Boolean get() = clients.isNotEmpty()
    val hasStatsSubscribers: Boolean get() = clients.any { it.wantsStats }

    fun broadcast(line: String) {
        clients.forEach { it.send(line) }
    }

    fun broadcastStats(line: String) {
        clients.filter { it.wantsStats }.forEach { it.send(line) }
    }

    /** Blocks forever. */
    fun serve() {
        val server = LocalServerSocket(Proto.SOCKET_NAME)
        logi("listening on @${Proto.SOCKET_NAME}")
        while (true) {
            val s = server.accept()
            val uid = try {
                s.peerCredentials.uid
            } catch (_: IOException) {
                -1
            }
            if (!isAllowed(uid)) {
                logw("rejecting client uid=$uid")
                s.close()
                continue
            }
            val c = Client(s, uid)
            clients += c
            logi("client uid=$uid connected")
            c.start()
        }
    }
}
