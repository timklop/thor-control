#!/system/bin/sh
sh "${0%/*}/restore-kernel.sh"
sh "${0%/*}/sensor-orient.sh" revert
rm -rf /data/adb/thorctl

# Removing the module normally goes through the Magisk app, where thorctld sees the flag and
# service.sh reverts everything while Android runs. If this runs at early boot instead, give the
# disabled apps back once the package manager is up (the module files are gone by then).
(
  until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
  for p in com.android.launcher3 com.android.se; do
    pm list packages -d | grep -qx "package:$p" && pm enable "$p"
  done
) >/dev/null 2>&1 &
