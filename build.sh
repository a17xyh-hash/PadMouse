#!/usr/bin/env bash
# 无 Gradle 构建：aapt2 -> javac -> d8 -> zip -> apksigner
set -e
export JAVA_HOME=/workspace/jdk17
export PATH=$JAVA_HOME/bin:$PATH

SDK=/workspace/Android
BT=$SDK/build-tools/35.0.0
APP=/workspace/PadMouse/app
B=/workspace/PadMouse/build
MID=$B/mid
KS=${KS:-/workspace/DeepSeekPet/debug.keystore}
OUT=${1:-/workspace/PadMouse/PadMouse-0.1.apk}
VER_CODE=${VER_CODE:-1}
VER_NAME=${VER_NAME:-0.1}

rm -rf "$B/gen" "$B/classes" "$B/dex" "$MID"
mkdir -p "$B/gen" "$B/classes" "$B/dex" "$MID"

echo "[1/6] aapt2 compile"
$BT/aapt2 compile --dir "$APP/src/main/res" -o "$MID/res.zip"

echo "[2/6] aapt2 link"
$BT/aapt2 link -o "$MID/base.apk" \
  -I "$SDK/platforms/android-35/android.jar" \
  --manifest "$APP/src/main/AndroidManifest.xml" \
  -R "$MID/res.zip" --java "$B/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VER_CODE" --version-name "$VER_NAME" --auto-add-overlay

echo "[3/6] javac"
javac -encoding UTF-8 -source 17 -target 17 -nowarn \
  -classpath "$SDK/platforms/android-35/android.jar" \
  -d "$B/classes" \
  $(find "$B/gen" -name '*.java') \
  $(find "$APP/src/main/java" -name '*.java')

echo "[4/6] d8"
$BT/d8 --release --lib "$SDK/platforms/android-35/android.jar" \
  --min-api 26 --output "$B/dex" \
  $(find "$B/classes" -name '*.class')

echo "[5/6] package dex"
node /workspace/DeepSeekPet/mkzip.js "$MID/base.apk" "$B/dex/classes.dex" "$MID/unsigned.apk" >/dev/null

echo "[6/6] sign"
$BT/apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT" "$MID/unsigned.apk"
$BT/apksigner verify --print-certs "$OUT" | head -4
chmod 644 "$OUT" 2>/dev/null || true
echo "BUILT: $OUT ($(stat -c%s "$OUT") bytes)"
