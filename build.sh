#!/bin/bash
# Manual headless build (no Gradle): javac -> d8 -> aapt2 -> zipalign -> apksigner
set -e
cd "$(dirname "$0")"
SDK=$HOME/workspace/apps/android-sdk
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
mkdir -p build/classes build/dex build/res_compiled dist
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass android -keypass android \
  -dname "CN=Android Debug,O=Personal,C=US"
javac --release 8 -encoding UTF-8 -cp "$JAR" -d build/classes \
  src/com/aman/photosbridge/MainActivity.java
$BT/d8 --lib "$JAR" --min-api 26 --output build/dex \
  build/classes/com/aman/photosbridge/*.class
$BT/aapt2 compile --dir res -o build/res_compiled/res.zip
$BT/aapt2 link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml \
  build/res_compiled/res.zip \
  --min-sdk-version 26 --target-sdk-version 35
(cd build && zip -q unsigned.apk -j dex/classes.dex)
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk
$BT/apksigner sign --ks debug.keystore --ks-pass pass:android \
  --key-pass pass:android --out dist/photosbridge-v0.3.apk build/aligned.apk
echo "Built dist/photosbridge-v0.3.apk"
