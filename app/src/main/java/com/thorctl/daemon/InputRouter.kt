package com.thorctl.daemon

import android.view.InputDevice
import com.thorctl.shared.Proto

/**
 * Routes the built-in controller to a display by associating its input device descriptor with
 * the display's unique id (the same mechanism LineageOS AynParts uses). "free" removes the
 * association so the controller follows the focused display.
 */
internal class InputRouter(private val displays: DisplayController) {
    @Volatile
    var target = Proto.TARGET_FREE
        private set

    private fun descriptor(): String {
        return try {
            val devs = SystemServices.inputDevices()
            devs.firstOrNull { it.descriptor == KNOWN_DESCRIPTOR }?.descriptor
                ?: devs.firstOrNull {
                    it.name == CONTROLLER_NAME && !it.isExternal &&
                        (it.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                }?.descriptor
                ?: KNOWN_DESCRIPTOR
        } catch (e: Throwable) {
            KNOWN_DESCRIPTOR
        }
    }

    fun apply(t: String) {
        val desc = descriptor()
        try {
            when (t) {
                Proto.TARGET_TOP -> {
                    if (displays.topUniqueId.isEmpty()) return
                    SystemServices.associateByDescriptor(desc, displays.topUniqueId)
                }
                Proto.TARGET_BOTTOM -> {
                    if (displays.bottomUniqueId.isEmpty()) return
                    SystemServices.associateByDescriptor(desc, displays.bottomUniqueId)
                }
                else -> SystemServices.removeAssociationByDescriptor(desc)
            }
            target = t
            logi("controller -> $t ($desc)")
        } catch (e: Throwable) {
            logw("controller routing failed", e)
        }
    }

    companion object {
        const val KNOWN_DESCRIPTOR = "90dfbbe0bbbab74642c53cae4ae0aa640d40ec21"
        const val CONTROLLER_NAME = "Xbox Wireless Controller"
    }
}
