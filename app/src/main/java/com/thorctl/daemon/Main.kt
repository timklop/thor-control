package com.thorctl.daemon

import java.io.File
import kotlin.system.exitProcess

/**
 * Root daemon entry point. Started by the Magisk module's service.sh supervisor:
 *
 *   CLASSPATH=<apk> app_process /system/bin --nice-name=thorctld com.thorctl.daemon.Main
 *
 * If it exits for any reason the supervisor restores kernel fan control and restarts it.
 */
object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            logw("uncaught exception in ${t.name}", e)
            if (t.name == "main") exitProcess(1)
        }
        try {
            // Immune to the kernel OOM killer; lmkd only manages app processes anyway.
            File("/proc/self/oom_score_adj").writeText("-1000")
        } catch (e: Exception) {
            logw("could not set oom_score_adj", e)
        }
        logi("thorctld starting, pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()}")
        try {
            Daemon().run()
        } catch (e: Throwable) {
            logw("daemon died", e)
            exitProcess(1)
        }
    }
}
