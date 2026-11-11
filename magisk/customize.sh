#!/system/bin/sh
ui_print "- Thor Control"
ui_print "  Root service (thorctld) + persistent system app"

if [ "$(getprop ro.product.device)" != "odin2thor" ]; then
  ui_print "! Warning: built for the AYN Thor (odin2thor)."
fi

# PackageManager caches parsed system apps keyed on the app directory's mtime. Magisk recreates
# that directory at early boot (clock still at 1970), so an updated APK would be ignored.
rm -f /data/system/package_cache/*/ThorControl-* 2>/dev/null

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/restore-kernel.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/sensor-orient.sh" 0 0 0755
set_perm "$MODPATH/unhide-apps.sh" 0 0 0755
set_perm "$MODPATH/system-tweaks.sh" 0 0 0755
ui_print "- Reboot to finish"
