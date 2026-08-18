#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/decentricity/Android/Sdk}}"
if [[ ! -d "$SDK/platforms" ]]; then
  SDK="/home/decentricity/android-sdk"
fi
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
BT="${BT%/}"
ANDROID_JAR="$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)/android.jar"
KEYSTORE="${HOME}/.android/debug.keystore"

if [[ ! -x "$BT/aapt2" || ! -f "$ANDROID_JAR" ]]; then
  echo "Missing Android SDK tools. aapt2=$BT/aapt2 android.jar=$ANDROID_JAR" >&2
  exit 1
fi
if [[ ! -f "$KEYSTORE" ]]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

OUT="$ROOT/out"
GEN="$OUT/gen"
OBJ="$OUT/obj"
mkdir -p "$OUT" "$GEN" "$OBJ"
rm -f "$OUT"/*.apk "$OUT"/classes.dex "$OUT"/res.zip

"$BT/aapt2" compile --dir "$ROOT/res" -o "$OUT/res.zip"
"$BT/aapt2" link \
  -o "$OUT/unsigned-unaligned.apk" \
  --manifest "$ROOT/AndroidManifest.xml" \
  -I "$ANDROID_JAR" \
  --java "$GEN" \
  --auto-add-overlay \
  --min-sdk-version 25 \
  --target-sdk-version 28 \
  --version-code 1 \
  --version-name 1.0 \
  "$OUT/res.zip"

find "$GEN" -name 'R.java' >/dev/null

javac --release 8 -cp "$ANDROID_JAR" -d "$OBJ" \
  "$GEN"/com/decentricity/arlauncher/R.java \
  "$ROOT"/src/com/decentricity/arlauncher/*.java

"$BT/d8" --min-api 25 --output "$OUT" $(find "$OBJ" -name '*.class')
unzip -qo "$OUT/unsigned-unaligned.apk" -d "$OUT/apk"
cp "$OUT/classes.dex" "$OUT/apk/classes.dex"
(cd "$OUT/apk" && zip -qr "$OUT/unsigned-unaligned.apk" .)

"$BT/zipalign" -f -p 4 "$OUT/unsigned-unaligned.apk" "$OUT/unsigned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$ROOT/ar-launcher.apk" "$OUT/unsigned.apk"
"$BT/apksigner" verify "$ROOT/ar-launcher.apk"
"$BT/aapt" dump badging "$ROOT/ar-launcher.apk" | head -20
echo "Built $ROOT/ar-launcher.apk"
