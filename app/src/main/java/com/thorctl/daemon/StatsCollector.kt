package com.thorctl.daemon

import com.thorctl.shared.Stats
import java.io.File
import kotlin.math.abs

internal class StatsCollector(private val fan: FanController) {
    private var lastTotal = 0L
    private var lastIdle = 0L

    private val policies: List<String> = File("/sys/devices/system/cpu/cpufreq").listFiles()
        ?.filter { it.name.startsWith("policy") }
        ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: 0 }
        ?.map { it.path }
        ?: emptyList()

    private val cpuZones: List<String>
    private val gpuZones: List<String>

    init {
        val cpu = ArrayList<String>()
        val gpu = ArrayList<String>()
        File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") }?.forEach {
            val type = Sys.read("${it.path}/type") ?: return@forEach
            when {
                type.startsWith("cpu-") || type.startsWith("cpuss-") -> cpu += "${it.path}/temp"
                type.startsWith("gpuss-") -> gpu += "${it.path}/temp"
            }
        }
        cpuZones = cpu
        gpuZones = gpu
    }

    private fun maxTemp(paths: List<String>): Float =
        paths.mapNotNull { Sys.readInt(it) }.filter { it in 1..200000 }.maxOrNull()?.let { it / 1000f } ?: 0f

    private fun cpuLoad(): Float {
        val line = Sys.read("/proc/stat")?.lineSequence()?.firstOrNull() ?: return 0f
        val v = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (v.size < 5) return 0f
        val idle = v[3] + v[4]
        val total = v.take(8).sum()
        val dt = total - lastTotal
        val di = idle - lastIdle
        lastTotal = total
        lastIdle = idle
        return if (dt <= 0) 0f else ((dt - di).toFloat() / dt).coerceIn(0f, 1f)
    }

    private fun meminfo(): Pair<Int, Int> {
        var total = 0
        var avail = 0
        Sys.read("/proc/meminfo")?.lineSequence()?.forEach { l ->
            when {
                l.startsWith("MemTotal:") -> total = l.split(Regex("\\s+"))[1].toInt() / 1024
                l.startsWith("MemAvailable:") -> avail = l.split(Regex("\\s+"))[1].toInt() / 1024
            }
        }
        return total to avail
    }

    fun collect(): Stats {
        val freqs = policies.map { (Sys.readInt("$it/scaling_cur_freq") ?: 0) / 1000 }
        val gpuBusy = Sys.read("$KGSL/gpu_busy_percentage")?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        val gpuClk = (Sys.readLong("$KGSL/gpuclk") ?: 0L) / 1_000_000
        val gpuTemp = Sys.readInt("$KGSL/temp")?.let { it / 1000f } ?: maxTemp(gpuZones)
        val (total, avail) = meminfo()

        val bv = (Sys.readLong("$BAT/voltage_now") ?: 0L) / 1e6
        val bi = (Sys.readLong("$BAT/current_now") ?: 0L) / 1e6
        val usbOnline = Sys.readInt("$USB/online") == 1
        val uv = if (usbOnline) (Sys.readLong("$USB/voltage_now") ?: 0L) / 1e6 else 0.0
        val ui = if (usbOnline) (Sys.readLong("$USB/current_now") ?: 0L) / 1e6 else 0.0

        return Stats(
            cpuLoad = cpuLoad(),
            cpuFreqMhz = freqs.maxOrNull() ?: 0,
            clusterFreqsMhz = freqs,
            cpuTemp = maxTemp(cpuZones),
            gpuLoad = gpuBusy / 100f,
            gpuFreqMhz = gpuClk.toInt(),
            gpuTemp = gpuTemp,
            ramTotalMb = total,
            ramAvailMb = avail,
            batteryLevel = Sys.readInt("$BAT/capacity") ?: 0,
            batteryStatus = Sys.read("$BAT/status") ?: "Unknown",
            batteryWatts = abs(bv * bi).toFloat(),
            inputWatts = abs(uv * ui).toFloat(),
            batteryTemp = (Sys.readInt("$BAT/temp") ?: 0) / 10f,
            fanPwm = fan.readPwm(),
            fanTemp = fan.controlTemp,
        )
    }

    companion object {
        const val KGSL = "/sys/class/kgsl/kgsl-3d0"
        const val BAT = "/sys/class/power_supply/battery"
        const val USB = "/sys/class/power_supply/usb"
    }
}
