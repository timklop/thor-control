package com.thorctl.app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thorctl.ui.MenuPanel
import com.thorctl.ui.ThorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Same look as the shade: wallpaper behind, blurred.
        window.setBackgroundBlurRadius(110)
        setContent { ThorTheme { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        DaemonClient.subscribeStats(true)
    }

    override fun onPause() {
        DaemonClient.subscribeStats(false)
        super.onPause()
    }

    @Composable
    private fun Screen() {
        val cs = MaterialTheme.colorScheme
        val connected by DaemonClient.connected.collectAsState()
        val a11y by OverlayController.a11yBound.collectAsState()
        val mode by OverlayController.mode.collectAsState()
        val isSystem = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isPersistent = (applicationInfo.flags and ApplicationInfo.FLAG_PERSISTENT) != 0

        Row(
            Modifier
                .fillMaxSize()
                .background(com.thorctl.ui.qsColors().scrim)
                .safeDrawingPadding()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.widthIn(max = 300.dp).fillMaxHeight()) {
                Text("Thor Control", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium), color = cs.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Press the AYN button to open the menu on the bottom screen. Hold it with a button for shortcuts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                com.thorctl.ui.ShortcutList()
                Spacer(Modifier.height(16.dp))
                StatusRow("Root service", if (connected) "Running" else "Not running", connected)
                StatusRow("Menu layer", if (a11y) "Accessibility overlay" else "App overlay (fallback)", a11y)
                StatusRow("System app", if (isSystem) "Yes" else "No – install the Magisk module", isSystem)
                StatusRow("Persistent", if (isPersistent) "Yes" else "No", isPersistent)
                if (mode != "none") StatusRow("Last shown as", mode, true)
                Spacer(Modifier.weight(1f))
                Button(onClick = { OverlayController.show() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Show menu on bottom screen")
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = {
                        try {
                            startActivity(
                                Intent().setComponent(ComponentName("org.lineageos.settings.ayn", "org.lineageos.settings.ayn.AynSettingsActivity")),
                            )
                        } catch (_: Exception) {
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Controller calibration & mapping") }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(32.dp))
                    .background(com.thorctl.ui.qsColors().scrim),
            ) {
                MenuPanel(entered = true, onOpenApp = null, modifier = Modifier.fillMaxSize())
            }
        }
    }

    @Composable
    private fun StatusRow(label: String, value: String, ok: Boolean) {
        val cs = MaterialTheme.colorScheme
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) Color(0xFF7BD88F) else cs.error))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            }
        }
    }
}
