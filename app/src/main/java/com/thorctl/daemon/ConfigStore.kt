package com.thorctl.daemon

import com.thorctl.shared.FanProfile
import com.thorctl.shared.GameOptions
import com.thorctl.shared.LedState
import com.thorctl.shared.Proto
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persistent daemon settings in /data/adb/thorctl/config.json (root-only directory). */
internal class ConfigStore(private val file: File) {
    var fanProfile: String = "smart"
    val customProfiles = ArrayList<FanProfile>()
    var controller: String = Proto.TARGET_FREE
    var leds: LedState = LedState()
    var keepAccessibility: Boolean = true
    var gameOptions: GameOptions = GameOptions()

    fun load() {
        try {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            fanProfile = o.optString("fanProfile", fanProfile)
            customProfiles.clear()
            o.optJSONArray("customProfiles")?.let { arr ->
                for (i in 0 until arr.length()) customProfiles += FanProfile.fromJson(arr.getJSONObject(i))
            }
            controller = o.optString("controller", controller)
            leds = LedState.fromJson(o.optJSONObject("leds"))
            keepAccessibility = o.optBoolean("keepAccessibility", true)
            gameOptions = GameOptions.fromJson(o.optJSONObject("gameOptions"))
            logi("config loaded: fan=$fanProfile controller=$controller custom=${customProfiles.size}")
        } catch (e: Exception) {
            logw("config load failed, using defaults", e)
        }
    }

    @Synchronized
    fun save() {
        try {
            file.parentFile?.mkdirs()
            val o = JSONObject().apply {
                put("fanProfile", fanProfile)
                put("customProfiles", JSONArray().apply { customProfiles.forEach { put(it.toJson()) } })
                put("controller", controller)
                put("leds", leds.toJson())
                put("keepAccessibility", keepAccessibility)
                put("gameOptions", gameOptions.toJson())
            }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString(2))
            tmp.renameTo(file)
        } catch (e: Exception) {
            logw("config save failed", e)
        }
    }

    fun allProfiles(): List<FanProfile> = FanProfile.BUILTINS + customProfiles

    fun profile(id: String): FanProfile? = allProfiles().firstOrNull { it.id == id }
}
