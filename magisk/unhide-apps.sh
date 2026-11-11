#!/system/bin/sh
# Make LineageOS apps visible in Cocoon's "All apps".
#
# Cocoon hides built-in, never-updated system apps unless their package name contains a
# Google-style keyword (browser, gallery, chrome, ...). LineageOS's apps use codenames
# (org.lineageos.jelly, org.lineageos.glimpse), so they disappear. Installing each app's own
# system APK as an "update" makes it count as an installed app.
#
# Runs every boot from service.sh. It only reinstalls when the app is not an updated system app
# yet, or when a LineageOS update changed the system APK (version codes don't change between
# nightlies, so the copy would otherwise go stale).
#
# Revert one app: pm uninstall-system-updates <package>
APPS="org.lineageos.jelly:/product/app/Jelly/Jelly.apk org.lineageos.glimpse:/product/app/Glimpse/Glimpse.apk"
STATE=/data/adb/thorctl/unhide
mkdir -p "$STATE"

for entry in $APPS; do
  pkg=${entry%%:*}
  apk=${entry#*:}
  [ -f "$apk" ] || continue
  sum=$(sha256sum "$apk" | cut -d' ' -f1)
  case "$(dumpsys package "$pkg" 2>/dev/null | grep -m1 'pkgFlags=')" in
    *UPDATED_SYSTEM_APP*) updated=1 ;;
    *) updated=0 ;;
  esac
  [ "$updated" = 1 ] && [ "$(cat "$STATE/$pkg" 2>/dev/null)" = "$sum" ] && continue
  tmp=/data/local/tmp/unhide-$pkg.apk
  cp "$apk" "$tmp" && chmod 0644 "$tmp"
  if pm install -r "$tmp" >/dev/null 2>&1; then
    echo "$sum" > "$STATE/$pkg"
    echo "unhide-apps: installed $pkg from $apk"
  else
    echo "unhide-apps: install of $pkg failed"
  fi
  rm -f "$tmp"
done
