#!/bin/sh
# Builds the release APK and packs it into a flashable Magisk module zip at out/ThorControl-magisk.zip
set -e
cd "$(dirname "$0")"

# The Android Gradle plugin needs JDK 17. Use JAVA_HOME if set, otherwise look in the usual places.
if [ -z "$JAVA_HOME" ]; then
  for j in "$(/usr/libexec/java_home -v 17 2>/dev/null)" /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 \
    /usr/lib/jvm/java-17-openjdk* /usr/lib/jvm/temurin-17*; do
    if [ -n "$j" ] && [ -x "$j/bin/java" ]; then export JAVA_HOME="$j"; break; fi
  done
fi

# Android SDK: sdk.dir in local.properties, or ANDROID_HOME / ANDROID_SDK_ROOT.
SDK=$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)
SDK=${SDK:-${ANDROID_HOME:-$ANDROID_SDK_ROOT}}
if [ -z "$SDK" ] || [ ! -d "$SDK/build-tools" ]; then
  echo "Android SDK not found: set sdk.dir in local.properties or ANDROID_HOME" >&2
  exit 1
fi
export ANDROID_HOME="$SDK"

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
# Any key will do; the standard debug key is created if this machine has none yet.
DEBUG_KS="$HOME/.android/debug.keystore"
if [ ! -f "$DEBUG_KS" ]; then
  mkdir -p "$HOME/.android"
  keytool -genkeypair -keystore "$DEBUG_KS" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi
BT=$(ls -d "$SDK"/build-tools/*/ | tail -n1)
PLATFORM=$(ls -d "$SDK"/platforms/android-*/ | tail -n1)
OV=build/overlay
rm -rf "$OV" && mkdir -p "$OV" "$STAGE/system/product/overlay"
"$BT/aapt2" compile --dir overlay/res -o "$OV/res.zip"
"$BT/aapt2" link -o "$OV/unsigned.apk" -I "$PLATFORM/android.jar" --manifest overlay/AndroidManifest.xml \
  --min-sdk-version 34 --target-sdk-version 36 "$OV/res.zip"
"$BT/zipalign" -f 4 "$OV/unsigned.apk" "$OV/aligned.apk"
"$BT/apksigner" sign --v4-signing-enabled false --ks "$DEBUG_KS" --ks-pass pass:android --key-pass pass:android \
  --out "$STAGE/system/product/overlay/ThorControlFrameworkOverlay.apk" "$OV/aligned.apk"

rm -f out/ThorControl-magisk.zip
(cd "$STAGE" && zip -qr9 ../../out/ThorControl-magisk.zip . -x '.DS_Store')
echo "built out/ThorControl-magisk.zip ($(du -h out/ThorControl-magisk.zip | cut -f1))"
