#!/system/bin/sh
# Magisk's Action button toggles top-screen Android navigation for the next boot.
set -e
DATA=/data/adb/thorctl
KEEP_GESTURES=$DATA/keep-top-gestures

if [ -f "$KEEP_GESTURES" ]; then
  rm "$KEEP_GESTURES"
  echo "Thor Control default navigation selected."
else
  umask 077
  mkdir -p "$DATA"
  # SystemUI resolves Quickstep during startup. Enable it before the reboot so
  # it is already available when Android starts.
  pm enable com.android.launcher3
  touch "$KEEP_GESTURES"
  echo "Top-screen Android gesture navigation selected."
fi

echo "Reboot to apply this change."
