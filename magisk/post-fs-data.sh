#!/system/bin/sh
# Runs before Magisk mounts module files, so /vendor still shows the stock files here.
# Every overlay is regenerated from the stock file on each boot, so LineageOS updates to those
# files are picked up automatically and only our one-line changes are applied on top.
MODDIR=${0%/*}

overlay() { # overlay <stock path> <selinux context> <sed program>
  SRC=$1
  DST=$MODDIR/system$1
  [ -f "$SRC" ] || return
  mkdir -p "${DST%/*}"
  sed "$3" "$SRC" > "$DST.tmp" && mv -f "$DST.tmp" "$DST"
  chmod 0644 "$DST"
  chcon "$2" "$DST"
}

# 1. Bottom panel gets its own brightness instead of following the top one.
overlay /vendor/etc/displayconfig/display_layout_configuration.xml \
  u:object_r:vendor_configs_file:s0 '/leadDisplayAddress/d'

# 2. No status bar, notification shade or navigation/task bar on the bottom panel.
#    isHomeSupported stays true, so the bottom launcher still works.
overlay /vendor/etc/display_settings.xml \
  u:object_r:vendor_configs_file:s0 's/shouldShowSystemDecors="true"/shouldShowSystemDecors="false"/'

# 3. Controller Home (KEY_HOME 102) is handled by thorctld: short press = Home,
#    long press = app switcher. Remove Android's own mapping so it does not fire twice.
overlay /vendor/usr/keylayout/Vendor_045e_Product_028e_Version_1234.kl \
  u:object_r:vendor_keylayout_file:s0 '/^key[[:space:]]\{1,\}102[[:space:]]\{1,\}HOME/d'

# 4. Sensor axes for auto-rotate / tilt (see sensor-orient.sh). Runs before the sensor stack
#    starts, so a regenerated registry is corrected on the same boot.
sh "$MODDIR/sensor-orient.sh" apply
