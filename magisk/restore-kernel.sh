#!/system/bin/sh
# Hand the kernel back its defaults whenever thorctld is not running:
#  - fan trip points (saved by thorctld on first start each boot)
#  - CPU/GPU floors raised by game mode (saved before game mode writes them)
F=/dev/thorctl/trips.orig
if [ -f "$F" ]; then
  while read -r path value; do
    [ -n "$path" ] && [ -n "$value" ] && echo "$value" > "$path"
  done < "$F"
fi

G=/dev/thorctl/game.orig
if [ -f "$G" ]; then
  while read -r path value; do
    [ -n "$path" ] && [ -n "$value" ] && echo "$value" > "$path"
  done < "$G"
  rm -f "$G"
fi
