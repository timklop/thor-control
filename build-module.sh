#!/bin/sh
# Builds the release APK and packs it into a flashable Magisk module zip at out/ThorControl-magisk.zip
set -e
cd "$(dirname "$0")"

./gradlew -q assembleRelease

STAGE=build/module
rm -rf "$STAGE"
mkdir -p "$STAGE/system/app/ThorControl" out
cp -R magisk/. "$STAGE/"
# post-fs-data.sh regenerates this from the device's stock file on every boot.
mkdir -p "$STAGE/system/vendor/etc/displayconfig"
rm -f "$STAGE/system/vendor/etc/displayconfig/.gitkeep"
cp app/build/outputs/apk/release/app-release.apk "$STAGE/system/app/ThorControl/ThorControl.apk"

# Framework resource overlay (no file pinning), installed as a static overlay on /product.
SDK=$(sed -n 's/^sdk.dir=//p' local.properties)
BT=$(ls -d "$SDK"/build-tools/*/ | tail -n1)
PLATFORM=$(ls -d "$SDK"/platforms/android-*/ | tail -n1)
OV=build/overlay
rm -rf "$OV" && mkdir -p "$OV" "$STAGE/system/product/overlay"
"$BT/aapt2" compile --dir overlay/res -o "$OV/res.zip"
"$BT/aapt2" link -o "$OV/unsigned.apk" -I "$PLATFORM/android.jar" --manifest overlay/AndroidManifest.xml \
  --min-sdk-version 34 --target-sdk-version 36 "$OV/res.zip"
"$BT/zipalign" -f 4 "$OV/unsigned.apk" "$OV/aligned.apk"
"$BT/apksigner" sign --v4-signing-enabled false --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$STAGE/system/product/overlay/ThorControlFrameworkOverlay.apk" "$OV/aligned.apk"

rm -f out/ThorControl-magisk.zip
(cd "$STAGE" && zip -qr9 ../../out/ThorControl-magisk.zip . -x '.DS_Store')
echo "built out/ThorControl-magisk.zip ($(du -h out/ThorControl-magisk.zip | cut -f1))"
