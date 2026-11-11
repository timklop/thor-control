#!/system/bin/sh
# Memory savings applied after boot, and undone when the module is disabled or removed.
#
#   apply   service.sh, every boot (after boot_completed)
#   revert  service.sh, when the module is disabled/removed from the Magisk app
#
# The navigation bar is hidden by system.prop (qemu.hw.mainkeys=1) and file pinning is switched
# off by the framework overlay in /product/overlay; both go away with the module on reboot.
MODE=${1:-apply}

LAUNCHER3=com.android.launcher3 # Trebuchet: only the gesture-nav / recents provider here
SE=com.android.se               # Disabled by v1.7 builds; Android 17 restarts it anyway, so undone
SERVICES="cameraserver wifidisplayhalservice" # no cameras; Wi-Fi Display (Miracast) casting

disabled() { pm list packages -d 2>/dev/null | grep -qx "package:$1"; }

# Another launcher must be able to take Home, or the device would have none.
other_home() {
  cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null |
    grep '/' | grep -v -e "$LAUNCHER3/" -e 'com.android.settings/' | grep -q .
}

zram_zstd() {
  Z=/sys/block/zram0
  [ -f $Z/comp_algorithm ] || return
  grep -q 'zstd' $Z/comp_algorithm || return
  grep -q '\[zstd\]' $Z/comp_algorithm && return
  size=$(cat $Z/disksize)
  prio=$(awk '$1 == "/dev/block/zram0" { print $5 }' /proc/swaps)
  [ "$size" -gt 0 ] 2>/dev/null || return
  # swapoff pulls swapped pages back into RAM; skip if that fails (memory pressure).
  if swapoff /dev/block/zram0 2>/dev/null; then
    echo 1 > $Z/reset
    echo zstd > $Z/comp_algorithm
    echo "$size" > $Z/disksize
    mkswap /dev/block/zram0 >/dev/null 2>&1
    swapon -p "${prio:-32758}" /dev/block/zram0 2>/dev/null || swapon /dev/block/zram0
    echo "system-tweaks: zram now $(cat $Z/comp_algorithm)"
  fi
}

case "$MODE" in
  apply)
    disabled $SE && pm enable $SE >/dev/null 2>&1
    if other_home; then
      disabled $LAUNCHER3 || { pm disable $LAUNCHER3 >/dev/null 2>&1 && echo "system-tweaks: disabled $LAUNCHER3"; }
    elif disabled $LAUNCHER3; then
      pm enable $LAUNCHER3 >/dev/null 2>&1 && echo "system-tweaks: no other launcher, re-enabled $LAUNCHER3"
    fi
    for s in $SERVICES; do
      [ "$(getprop init.svc.$s)" = running ] && stop "$s" && echo "system-tweaks: stopped $s"
    done
    zram_zstd
    ;;
  revert)
    disabled $SE && pm enable $SE >/dev/null 2>&1
    disabled $LAUNCHER3 && pm enable $LAUNCHER3 >/dev/null 2>&1
    for s in $SERVICES; do
      [ "$(getprop init.svc.$s)" = stopped ] && start "$s"
    done
    echo "system-tweaks: reverted"
    ;;
esac
