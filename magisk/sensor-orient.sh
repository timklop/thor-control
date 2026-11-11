#!/system/bin/sh
# Fix auto-rotate / tilt on the top screen.
#
# The IMU (SH5001 accel+gyro) and magnetometer (QMC6308) axis mappings in the Qualcomm sensor
# registry describe the top panel's native portrait frame, but LineageOS makes that panel
# landscape-native (ro.surface_flinger.primary_display_orientation=ORIENTATION_90). The sensor
# frame is therefore 90 degrees off and auto-rotate turns the top screen sideways. This rotates
# both sensors' device frames by 90 degrees about Z so gravity reads +Y when the Thor is held
# normally (verified on device).
#
# usage: sensor-orient.sh apply|revert
# Entries are only rewritten when they hold exactly the expected value, so an upstream fix
# (different mapping) is never clobbered. Writes keep inode, owner and SELinux label.
REG=/mnt/vendor/persist/sensors/registry/registry

entry() { # entry <name> <owner> <x> <y> <z>
  printf '{"%s":{"owner":"%s","x":{"type":"str","ver":"0","data":"%s"},"y":{"type":"str","ver":"0","data":"%s"},"z":{"type":"str","ver":"0","data":"%s"}}}' "$1" "$2" "$3" "$4" "$5"
}

swap() { # swap <file> <from json> <to json>
  F=$REG/$1
  [ -f "$F" ] || return 0
  [ "$(cat "$F")" = "$2" ] || return 0
  printf '%s' "$3" > "$F"
  echo "sensor-orient: rewrote $1"
}

SH_STOCK=$(entry sh5001_0_platform.orient sh5001 -x -y +z)
SH_FIXED=$(entry sh5001_0_platform.orient sh5001 -y +x +z)
QMC_STOCK=$(entry qmc6308_0_platform.orient sns_qmc6308 +x +y +z)
QMC_FIXED=$(entry qmc6308_0_platform.orient sns_qmc6308 +y -x +z)
# First attempt (v1.2 test build) rotated the wrong way round; migrate it.
SH_OLD=$(entry sh5001_0_platform.orient sh5001 +y -x +z)
QMC_OLD=$(entry qmc6308_0_platform.orient sns_qmc6308 -y +x +z)

case "$1" in
  apply)
    swap sh5001_0_platform.orient "$SH_STOCK" "$SH_FIXED"
    swap sh5001_0_platform.orient "$SH_OLD" "$SH_FIXED"
    swap qmc6308_0_platform.orient "$QMC_STOCK" "$QMC_FIXED"
    swap qmc6308_0_platform.orient "$QMC_OLD" "$QMC_FIXED"
    ;;
  revert)
    swap sh5001_0_platform.orient "$SH_FIXED" "$SH_STOCK"
    swap sh5001_0_platform.orient "$SH_OLD" "$SH_STOCK"
    swap qmc6308_0_platform.orient "$QMC_FIXED" "$QMC_STOCK"
    swap qmc6308_0_platform.orient "$QMC_OLD" "$QMC_STOCK"
    ;;
esac
