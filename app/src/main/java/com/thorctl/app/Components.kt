package com.thorctl.app

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.thorctl.shared.Proto

class ThorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DaemonClient.start()
        OverlayController.init(this)
    }
}

/**
 * Exists only to give the menu the accessibility overlay window layer. It requests no key
 * filtering and no window content, so it never sits in the input path of games.
 */
class ThorAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        OverlayController.onAccessibilityConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        OverlayController.onAccessibilityDisconnected(this)
        return false
    }

    override fun onDestroy() {
        OverlayController.onAccessibilityDisconnected(this)
        super.onDestroy()
    }
}

/** Fallback path used by the daemon when the app has no socket connection yet. */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Proto.ACTION_TOGGLE_MENU -> OverlayController.toggle()
            Proto.ACTION_HIDE_MENU -> OverlayController.hide()
        }
    }
}

/** Starting the process is all that is needed; ThorApp connects to the daemon. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}
