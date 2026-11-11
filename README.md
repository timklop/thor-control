# Thor Control

AYN Thor (LineageOS 24 / Android 17, Magisk) system controls: fan profiles and custom curves,
independent brightness per screen, controller-to-screen routing, joystick ring lights, a game
mode, a QS-style quick menu on the bottom screen bound to the AYN button, and its own recent apps
screen. The module also trims the system's memory use (see [Memory](#memory)).

## Install

```
./build-module.sh                      # -> out/ThorControl-magisk.zip
adb push out/ThorControl-magisk.zip /data/local/tmp/
adb shell su -c 'magisk --install-module /data/local/tmp/ThorControl-magisk.zip'
adb reboot
```

(or flash the zip from the Magisk app). Disabling/removing the module in Magisk hands the fan
back to the kernel and re-enables Trebuchet and the stopped services immediately; the navigation
bar and file pinning come back with the next reboot.

## Using it

* **AYN button (tap)** – open / close the menu on the bottom screen. It stays above everything
  until you press the button again. If the bottom screen is off, a tap turns it back on.
* **AYN shortcuts** (hold AYN, press the button; the controller is grabbed while AYN is held, so the
  game never sees these presses):

  | Shortcut | Action |
  |---|---|
  | AYN + D-pad ▼ | Controller → bottom screen |
  | AYN + D-pad ▲ | Controller → top screen |
  | AYN + D-pad ◀ / ▶ | Controller follows focus |
  | AYN + B | Move the app on the top screen to the bottom screen |
  | AYN + X | Move the app on the bottom screen to the top screen |
  | AYN + Y | Game mode on / off |
  | AYN + L1 / R1 | Bottom screen dimmer / brighter (10 % steps) |
  | AYN + L2 / R2 | Top screen dimmer / brighter (10 % steps) |

  A small confirmation pill appears on the affected screen.
* **Home (hold ~0.5 s)** – recent apps, on the screen the controller points to. The app you are in
  zooms out into its card; cards are all the same size, oldest on the left, the home screen always
  rightmost. ◀ ▶ (d-pad, left stick or bumpers; hold to keep moving) switch, **A** opens, **X**
  closes the app, **Y** closes all, **B** or AYN zooms back into where you came from. Moving onto
  the home card opens it. Opened on the home screen, home zooms out and the row moves to your most
  recent app. Touch: flick one card at a time, tap to open, swipe a card up to close it.
* **Home (press)** – inside an app, the app zooms out while the home screen slides in from the
  right (where it sits in recent apps) and zooms in.
* **Thor Keyboard** – the system keyboard. Whichever screen the text field is on, the keyboard
  appears on the bottom screen: the lower half is a staggered QWERTY keyboard in Cocoon's style,
  the upper half a touchpad. Keys work like the iPad keyboard: each shows its ?123 character small
  above it, and swiping down on the key types that character (it grows into place as the letter
  slides away); holding a key opens its accents (slide along the strip, release to type). The
  ?123 keys likewise swipe down to the =\< page. Shift: tap = one capital, double tap = caps lock,
  or L1 / R1 on the controller. Hold backspace to repeat; swipe left from it to select whole words
  back from the cursor (swipe back to give them back), and let go to delete them, like Gboard. Each key's touch area reaches the next
  key, so there is no dead space between keys. Swipe it to move the cursor one character at a time (a
  haptic tick per character); **Select** makes swipes extend a selection, then **Copy**; **Paste**
  inserts the clipboard. The touchpad shows the text around the cursor. The keyboard button
  (top right) hides it. Switch keyboards in Settings › System › Keyboard.
* **Top screen** – no navigation bar or taskbar (hardware Home and Back replace them), so apps get
  the full height.
* **Bottom screen** – no status bar, notification shade or navigation/task bar; its launcher still works.
  The screen-off button sits in the menu's header, next to the battery.
* **Game mode** (menu tile or AYN + Y) – the user-installed app in front on the top screen (or the
  next one you open) becomes "the game": every other app is closed, background apps are kept
  closed, all CPU clusters and the GPU are held at their top clock, the fan switches to Sport and the
  bottom screen turns off while the game is in front (each configurable on the tile's page). A game
  that shows something on the bottom screen keeps it on; tap AYN to turn it back on, and it then
  stays on for that game. The game itself is never closed, even while you switch away; when it
  exits, game mode waits for the next one. Clocks return to normal while the screen is off and when
  game mode is turned off. Thermal limits still apply on top.
* Tiles: tap the round icon for the quick action (cycle fan profile, cycle controller target,
  lights on/off), tap the rest of the tile for details.
* The launcher app "Thor Control" shows service status and the same menu on the top screen.

## How it works

| Piece | Where | Why |
|---|---|---|
| `thorctld` root daemon | `com.thorctl.daemon.*`, started by `magisk/service.sh` via `app_process` | Hardware + hidden framework APIs as uid 0. `oom_score_adj -1000`, restarted by the supervisor loop within 2 s. |
| Persistent system app | `com.thorctl.app.*`, mounted to `/system/app/ThorControl` | `android:persistent` → oom adj −800, never chosen by lmkd, restarted instantly by ActivityManager, survives force-stop. |
| Menu window | `TYPE_ACCESSIBILITY_OVERLAY` Dialog on the bottom display | Above all apps, status bar and IME; not hidden by `HIDE_OVERLAY_WINDOWS`. The accessibility service has no key filtering or content access, so it adds no input latency. Falls back to an app overlay if the service isn't bound. Hosted in a translucent Dialog so `Window.setBackgroundBlurRadius` (blur regions) works; `FLAG_BLUR_BEHIND` is ignored on the secondary display. |
| UI | `com.thorctl.ui.*`, Compose (BOM 2026.09) + Material 3 Expressive (`material3:1.5.0-rc01`), built with AGP 9.5 (built-in Kotlin 2.4), Gradle 9.9, compileSdk 37 | Mirrors the Android 17 shade: blurred tinted backdrop, 40 dp brightness tracks with a protruding handle, large tiles whose icon container morphs circle ↔ rounded square, wavy-ring stats, expressive spring motion. Opening ramps the blur while rows drop in on staggered springs. |
| Vendor file overlays | `magisk/post-fs-data.sh` | Regenerated from the stock files every boot (one-line edits): `display_layout_configuration.xml` without `leadDisplayAddress` (own brightness for the bottom panel), `display_settings.xml` with `shouldShowSystemDecors="false"` for the bottom panel (no bars/shade, `isHomeSupported` kept), and the controller key layout without `key 102 HOME` (Home handled by the daemon). |
| Sensor axes | `magisk/sensor-orient.sh` (post-fs-data / uninstall) | AYN mapped the IMU (SH5001) and magnetometer (QMC6308) for the top panel's native portrait frame, but LineageOS makes it landscape-native, so auto-rotate and tilt were 90° off. Rewrites `sh5001_0_platform.orient` to `-y,+x,+z` and `qmc6308_0_platform.orient` to `+y,-x,+z` in `/mnt/vendor/persist/sensors/registry/registry` (only when they hold the stock value; originals backed up in `/data/adb/thorctl/sensor-orient-backup`, restored on uninstall). |
| Cocoon app visibility | `magisk/unhide-apps.sh` (service.sh, every boot) | Cocoon's "All apps" hides never-updated system apps unless the package name contains a Google-style keyword (`browser`, `gallery`, …), which LineageOS's codenamed apps never match. Reinstalls the system APKs of Jelly (Browser) and Glimpse (Gallery) as updates so Cocoon lists them; re-runs when a LineageOS update changes those APKs (SHA-256 tracked in `/data/adb/thorctl/unhide`). Revert with `pm uninstall-system-updates <package>`. |
| Recent apps + Home animation | `daemon/Recents.kt` + `app/RecentsController.kt`, `ui/Recents.kt` | Replaces Trebuchet's recents (Trebuchet stayed resident for it: ~65 MB). When Home goes down the daemon captures the screen (`screencap -j`, ~140 ms) before it knows whether it's a press or a hold, so the overlay's first frame is exactly what was on screen. The home screen is captured whenever it is showing and nothing of ours covers it (`/data/adb/thorctl/home-<display>.jpg`), so it is ready for a Home press inside an app. Other apps use the previews the system persists in `/data/system_ce/0/snapshots/` (`ITaskSnapshotManager.takeTaskSnapshot` refreshes apps visible on the other screen). The stage is one row of cards seen through a camera (zoom 1 = a card fills the screen exactly, zoom 0 = cards at 70 %); every transition is that camera moving (strong ease in-out, no overshoot). On a Home press the app sends `home_go` once its first frame is on screen, and only then does the daemon send Home, so the switch happens underneath. While recents is open the daemon grabs the controller (EVIOCGRAB, like AYN combos) and forwards its buttons (held directions repeat). Running tasks are brought to front by the daemon (`startActivityFromRecents`); tasks that are no longer running are launched by the app, because Android blocks that background launch for a root process. The overlay is an accessibility-overlay Dialog like the menu, not focusable (the game keeps running underneath), and it frees every bitmap when it closes. |
| Thor Keyboard | `app/ThorKeyboard.kt`, `app/KeyboardController.kt`, `ui/Keyboard.kt` | An `InputMethodService` whose own window, on the editing screen, is empty and zero-height (no insets, no touches), so apps are neither covered nor resized. The keys are an accessibility-overlay window on the bottom screen in the same process, typing through `getCurrentInputConnection()`. The touchpad reads the field once per swipe (`getExtractedText`) and moves with `setSelection` (anchor + moving end while selecting), stepping over surrogate pairs; fields that can't report text get arrow keys (with shift when selecting). Copy uses `getSelectedText` (refused for passwords), paste commits the clipboard text. Turns the bottom screen on if it was off. `service.sh` keeps it enabled in the keyboard list. |
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

Hardware used:

* AYN button: `KEY_F24` on `gpio-keys`, read straight from evdev (works regardless of the foreground app).
  The event node number changes between boots; it is found by name.
* Controller ("Xbox Wireless Controller" evdev): `EVIOCGRAB` while AYN is held (via `Os.ioctlInt`;
  released by closing the fd), then any button/axis that changed meanwhile is re-synced so nothing
  sticks. B = `BTN_EAST` (305), X = `BTN_NORTH` (307), D-pad = `ABS_HAT0X/Y`, Home = `KEY_HOME` (102).
* Moving apps: `IActivityTaskManager.moveRootTaskToDisplay` + `startActivityFromRecents` (so Launcher
  sees the task come to front).
* Fan: hwmon `pwmfan/pwm1` (0–255). Curve profiles raise only the fan-bound thermal trip points
  (CPU/GPU throttling trips untouched); "Auto" restores them. Above 90 °C the fan is forced to 100 % in every profile.
  With the screen off (lid closed) the fan stops in every profile, unless the chip is still above 62 °C
  or the charging port above 48 °C; then the profile runs until it has cooled 7 / 3 °C below that.
  Original trips are saved to `/dev/thorctl/trips.orig` and restored by `restore-kernel.sh` whenever the daemon exits.
* Lights: `/sys/class/leds/{left,right}:stick:{0..3}` (`multi_intensity` + `brightness`).
* Brightness / bottom power / routing: `IDisplayManager.setBrightness`, `requestDisplayPower`,
  `IInputManager.addUniqueIdAssociationByDescriptor`.

Settings live in `/data/adb/thorctl/config.json`; daemon log in `/data/adb/thorctl/daemon.log`.

## Signing

The installed app is signed with `keystore/thorctl.jks`, which is not in git. Keep a copy of it:
Android only accepts an update of the installed app when it is signed with the same key. Without
the keystore, `app/build.gradle.kts` falls back to the debug key, which builds fine but can't
update an existing install (uninstall the module and reinstall instead).

Building needs the Android SDK (`sdk.dir` in `local.properties`, also not in git) with build-tools
for `aapt2`, `zipalign` and `apksigner`, and JDK 17 (`org.gradle.java.home` in `gradle.properties`).

## Development

Android 17 refuses `adb install` updates of persistent apps, so releases go through the module
(`./build-module.sh`, install, reboot; `customize.sh` clears the stale package-cache entry).

For quick daemon-only iterations, overwrite the bind-mounted APK and let the supervisor restart it:

```
./gradlew assembleRelease && adb push app/build/outputs/apk/release/app-release.apk /data/local/tmp/tc.apk
echo 'cat /data/local/tmp/tc.apk > /data/adb/modules/thorctl/system/app/ThorControl/ThorControl.apk; kill $(pidof thorctld)' | adb shell su
adb shell "CLASSPATH=\$(pm path com.thorctl | cut -d: -f2) app_process /system/bin com.thorctl.daemon.Cli '{\"cmd\":\"get_state\"}'"
```

Socket protocol: newline-delimited JSON on the abstract socket `@thorctl` (peer uid checked).
Commands: `hello`, `stats {on}`, `fan_profile {id}`, `fan_save {profile, activate}`, `fan_delete {id}`,
`brightness {display: top|bottom, value, final}`, `controller {target: free|top|bottom}`,
`move_app {to: top|bottom}`, `leds {state}`, `bottom_power {on}`, `keep_accessibility {on}`,
`game_mode {on}`, `game_options {options: {closeApps, cpu, gpu, fan, bottomOff}}`,
`recents_open {id}`, `recents_home`, `recents_remove {id}`, `recents_clear {ids}`, `recents_close`,
`home_go {seq}`, `overlay_done`.
Events: `state`, `stats`, `button {type: short}`, `hud {kind, display, ...}`,
`recents {display, origin, originTask, hero, home, tasks}`, `recents_thumb {id, thumb}`,
`recents_key {key}`, `recents_close`, `home_anim {seq, display, app, home}`.

`build-module.sh` also builds the framework overlay with the SDK's `aapt2`, `zipalign` and
`apksigner` (from `sdk.dir` in `local.properties`, signed with the debug keystore).
