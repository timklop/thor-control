# How Thor Control works

The [README](../README.md) covers what Thor Control does and how to use it. This page is for the
curious and for anyone building or changing it.

## The pieces

| Piece | Where | Why |
|---|---|---|
| `thorctld` root daemon | `com.thorctl.daemon.*`, started by `magisk/service.sh` via `app_process` | Hardware + hidden framework APIs as uid 0. `oom_score_adj -1000`, restarted by the supervisor loop within 2 s. Exits when the module is disabled or removed in Magisk, so `service.sh` can restore everything. |
| Persistent system app | `com.thorctl.app.*`, mounted to `/system/app/ThorControl` | `android:persistent` → oom adj −800, never chosen by lmkd, restarted instantly by ActivityManager, survives force-stop. |
| Menu window | `TYPE_ACCESSIBILITY_OVERLAY` Dialog on the bottom display | Above all apps, status bar and IME; not hidden by `HIDE_OVERLAY_WINDOWS`. The accessibility service has no key filtering or content access, so it adds no input latency. Falls back to an app overlay if the service isn't bound. Hosted in a translucent Dialog so `Window.setBackgroundBlurRadius` (blur regions) works; `FLAG_BLUR_BEHIND` is ignored on the secondary display. |
| UI | `com.thorctl.ui.*`, Compose (BOM 2026.09) + Material 3 Expressive (`material3:1.5.0-rc01`), built with AGP 9.5 (built-in Kotlin 2.4), Gradle 9.9, compileSdk 37 | Mirrors the Android 17 shade: blurred tinted backdrop, 40 dp brightness tracks with a protruding handle, large tiles whose icon container morphs circle ↔ rounded square, wavy-ring stats, expressive spring motion. Opening ramps the blur while rows drop in on staggered springs. |
| Vendor file overlays | `magisk/post-fs-data.sh` | Regenerated from the stock files every boot (one-line edits): `display_layout_configuration.xml` without `leadDisplayAddress` (own brightness for the bottom panel), `display_settings.xml` with `shouldShowSystemDecors="false"` for the bottom panel (no bars/shade, `isHomeSupported` kept), and the controller key layout without `key 102 HOME` (Home handled by the daemon). |
| Sensor axes | `magisk/sensor-orient.sh` (post-fs-data / uninstall) | AYN mapped the IMU (SH5001) and magnetometer (QMC6308) for the top panel's native portrait frame, but LineageOS makes it landscape-native, so auto-rotate and tilt were 90° off. Rewrites `sh5001_0_platform.orient` to `-y,+x,+z` and `qmc6308_0_platform.orient` to `+y,-x,+z` in `/mnt/vendor/persist/sensors/registry/registry` (only when they hold the stock value; originals backed up in `/data/adb/thorctl/sensor-orient-backup`, restored on uninstall). |
| Cocoon app visibility | `magisk/unhide-apps.sh` (service.sh, every boot) | Cocoon's "All apps" hides never-updated system apps unless the package name contains a Google-style keyword (`browser`, `gallery`, …), which LineageOS's codenamed apps never match. Reinstalls the system APKs of Jelly (Browser) and Glimpse (Gallery) as updates so Cocoon lists them; re-runs when a LineageOS update changes those APKs (SHA-256 tracked in `/data/adb/thorctl/unhide`). Revert with `pm uninstall-system-updates <package>`. |
| Recent apps + Home animation | `daemon/Recents.kt` + `app/RecentsController.kt`, `ui/Recents.kt` | Replaces Trebuchet's recents (Trebuchet stayed resident for it: ~65 MB). When Home goes down the daemon captures the screen (`screencap -j`, ~140 ms) before it knows whether it's a press or a hold, so the overlay's first frame is exactly what was on screen. The home screen is captured whenever it is showing and nothing of ours covers it (`/data/adb/thorctl/home-<display>.jpg`), so it is ready for a Home press inside an app. Other apps use the previews the system persists in `/data/system_ce/0/snapshots/` (`ITaskSnapshotManager.takeTaskSnapshot` refreshes apps visible on the other screen). The stage is one row of cards seen through a camera (zoom 1 = a card fills the screen exactly, zoom 0 = cards at 70 %); every transition is that camera moving (strong ease in-out, no overshoot). On a Home press the app sends `home_go` once its first frame is on screen, and only then does the daemon send Home, so the switch happens underneath. While recents is open the daemon grabs the controller (EVIOCGRAB, like AYN combos) and forwards its buttons (held directions repeat). Running tasks are brought to front by the daemon (`startActivityFromRecents`); tasks that are no longer running are launched by the app, because Android blocks that background launch for a root process. The overlay is an accessibility-overlay Dialog like the menu, not focusable (the game keeps running underneath), and it frees every bitmap when it closes. |
| Thor Keyboard | `app/ThorKeyboard.kt`, `app/KeyboardController.kt`, `ui/Keyboard.kt` | An `InputMethodService` whose own window, on the editing screen, is empty and zero-height (no insets, no touches), so apps are neither covered nor resized. The keys are an accessibility-overlay window on the bottom screen in the same process, typing through `getCurrentInputConnection()`. The touchpad reads the field once per swipe (`getExtractedText`) and moves with `setSelection` (anchor + moving end while selecting), stepping over surrogate pairs; fields that can't report text get arrow keys (with shift when selecting). The backspace swipe selects words back from the cursor and deletes the selection on release. Copy uses `getSelectedText` (refused for passwords), paste commits the clipboard text. L1 / R1 reach the keyboard through the daemon (it watches the controller while the keyboard reports itself visible), and the IME swallows them if they also arrive as key events. Turns the bottom screen on if it was off. `service.sh` keeps it enabled in the keyboard list. |
| Game mode | `GameMode` | Closing apps: user apps are force-stopped, system apps only lose background processes; the game, anything visible on either screen, the keyboard, accessibility services and core system processes are kept. Every ~10 s cached processes are cleared again. Clocks: each cpufreq policy's `scaling_min_freq` → `cpuinfo_max_freq`, kgsl `min_pwrlevel` → top level. Core parking (`core_ctl`) is left alone because the vendor perf HAL saves and rewrites it. On release the CPU floors go back to the values the vendor boot script sets (reading them back would capture the perf HAL's own boosts); everything written is saved in `/dev/thorctl/game.orig` and restored by `restore-kernel.sh` if the daemon dies. Bottom screen: turned off ~2 s after the game is in front, unless the game has an activity there or a window (Presentation) under that display in SurfaceFlinger's layer tree; back on when you leave the game. |
| Bottom panel watchdog | `DisplayController.healBottomPanel()` | LineageOS sometimes boots with the bottom panel dark (display ON in the framework, `card0-DSI-2` never enabled). If that persists for two checks, the daemon power-cycles the display (max 3 times per wake, 15 s apart). |

## Memory

What the module changes to leave more RAM for games (measured on the Thor, 8 GB):

| Change | Where | Effect |
|---|---|---|
| No file pinning | `overlay/` → static RRO in `/product/overlay` (`config_defaultPinnerServiceFiles` empty, home/WebView pin bytes 0) | PinnerService kept ~320 MB mlocked (framework/services jars, SystemUI, surfaceflinger, Cocoon's APK, WebView). Now reclaimable; what's in use stays cached. |
| Trebuchet disabled | `system-tweaks.sh` | ~65 MB; it only provided gesture navigation and recents here. Re-enabled automatically if no other launcher is installed. |
| No navigation bar | `system.prop` (`qemu.hw.mainkeys=1`) | Needed once Trebuchet is gone (its swipe gestures handled home/recents). |
| Unused services stopped | `system-tweaks.sh`, kept stopped by thorctld | `cameraserver` (no cameras) and the Wi-Fi Display / Miracast HAL. ~15 MB. (`com.android.se` is left alone: Android restarts it even when disabled.) |
| zstd swap | `system-tweaks.sh` | zram recompressed with zstd instead of lzo-rle: more fits in the 4 GB swap. |
| Bottom screen off while gaming | `GameMode` | The launcher on the bottom screen stops drawing and releases graphics memory (Cocoon: 30–120 MB depending on what it shows). |

Not changed on purpose: the phone service (SystemUI and Settings expect it), MediaProvider / file
picker / external storage (Cocoon saves artwork through them), Android's low-RAM mode (switches
features off), and kernel reservations (CMA, driver slab). thorctld itself costs ~55 MB, of which
~34 MB is the fixed price of any Java process started outside zygote on this ROM.

## Hardware used

* AYN button: `KEY_F24` on `gpio-keys`, read straight from evdev (works regardless of the foreground app).
  The event node number changes between boots; it is found by name.
* Controller ("Xbox Wireless Controller" evdev): `EVIOCGRAB` while AYN is held (via `Os.ioctlInt`;
  released by closing the fd), then any button/axis that changed meanwhile is re-synced so nothing
  sticks. B = `BTN_EAST` (305), X = `BTN_NORTH` (307), Y = `BTN_WEST` (308), L1 / R1 = `BTN_TL` /
  `BTN_TR` (310 / 311), L2 / R2 = analog `ABS_Z` / `ABS_RZ` (0–255) plus `BTN_TL2` / `BTN_TR2` when
  fully pulled, D-pad = `ABS_HAT0X/Y`, Home = `KEY_HOME` (102).
* Moving apps: `IActivityTaskManager.moveRootTaskToDisplay` + `startActivityFromRecents` (so Launcher
  sees the task come to front).
* Fan: hwmon `pwmfan/pwm1` (0–255). Curve profiles raise only the fan-bound thermal trip points
  (CPU/GPU throttling trips untouched); "Auto" restores them. Above 90 °C the fan is forced to 100 % in every profile.
  With the screen off (lid closed) the fan stops in every profile, unless the chip is still above 62 °C
  or the charging port above 48 °C; then the profile runs until it has cooled 7 / 3 °C below that.
  Original trips are saved to `/dev/thorctl/trips.orig` and restored by `restore-kernel.sh` whenever the daemon exits.
* Lights: `/sys/class/leds/{left,right}:stick:{0..3}` (`multi_intensity` + `brightness`).
* Brightness / bottom power / routing: `IDisplayManager.setBrightness`, `requestDisplayPower`,
  `IInputManager.addUniqueIdAssociationByDescriptor`. The brightness shortcuts step 10 % on the
  same perceptual curve as the sliders (SystemUI's HLG mapping).

Settings live in `/data/adb/thorctl/config.json`; the daemon log in `/data/adb/thorctl/daemon.log`
(and `logcat -s thorctld`).

## Building

```sh
./build-module.sh            # -> out/ThorControl-magisk.zip
```

Needs the Android SDK (`sdk.dir` in `local.properties`, not in git) with build-tools for `aapt2`,
`zipalign` and `apksigner` (the framework overlay is built and signed with the debug keystore),
and JDK 17 (`org.gradle.java.home` in `gradle.properties`).

### Signing

The released app is signed with `keystore/thorctl.jks`, which is not in git. Android only accepts
an update of the installed app when it is signed with the same key. Without the keystore,
`app/build.gradle.kts` falls back to the debug key: that builds fine, but can't update an existing
install (remove the module, reboot, then install your build).

## Development

Android 17 refuses `adb install` updates of persistent apps, so releases go through the module
(`./build-module.sh`, install, reboot; `customize.sh` clears the stale package-cache entry).

For quick iterations, overwrite the bind-mounted APK and restart the daemon and app (new
components such as services still need a reboot to be registered):

```sh
./gradlew assembleRelease && adb push app/build/outputs/apk/release/app-release.apk /data/local/tmp/tc.apk
echo 'cat /data/local/tmp/tc.apk > /data/adb/modules/thorctl/system/app/ThorControl/ThorControl.apk; kill $(pidof thorctld) $(pidof com.thorctl)' | adb shell su
adb shell "CLASSPATH=\$(pm path com.thorctl | cut -d: -f2) app_process /system/bin com.thorctl.daemon.Cli '{\"cmd\":\"get_state\"}'"
```

### Socket protocol

Newline-delimited JSON on the abstract socket `@thorctl` (peer uid checked).

Commands: `hello`, `stats {on}`, `fan_profile {id}`, `fan_save {profile, activate}`, `fan_delete {id}`,
`brightness {display: top|bottom, value, final}`, `controller {target: free|top|bottom}`,
`move_app {to: top|bottom}`, `leds {state}`, `bottom_power {on}`, `keep_accessibility {on}`,
`game_mode {on}`, `game_options {options: {closeApps, cpu, gpu, fan, bottomOff}}`,
`recents_open {id}`, `recents_home`, `recents_remove {id}`, `recents_clear {ids}`, `recents_close`,
`home_go {seq}`, `overlay_done`, `kb_visible {on}`.

Events: `state`, `stats`, `button {type: short}`, `hud {kind, display, ...}`,
`recents {display, origin, originTask, hero, home, tasks}`, `recents_thumb {id, thumb}`,
`recents_key {key}`, `recents_close`, `home_anim {seq, display, app, home}`, `kb_key {key}`,
`haptic`.
