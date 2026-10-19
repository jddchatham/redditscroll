#!/bin/sh
# Build RedditScroll APK entirely inside Termux.
# Requires: aapt2, ecj, dx, zipalign, apksigner, keytool (all in Termux repos)
set -e
cd "$(dirname "$0")"

# API-33 platform jar (real android.jar, works as aapt2 link framework).
ANDROID_JAR="$HOME/streamdeck-android/build/sdk33/android.jar"
KEYSTORE=debug.keystore
OUT=app/build/outputs/apk/debug/app-debug.apk

rm -rf build app/build
mkdir -p build/gen build/classes build/dex app/build/outputs/apk/debug

echo "== compile resources =="
aapt2 compile --dir res -o build/resources.zip

echo "== link apk =="
aapt2 link -o build/base.apk -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    build/resources.zip \
    --java build/gen \
    --min-sdk-version 21 --target-sdk-version 33

echo "== ecj (Java 7 bytecode for dx) =="
find src build/gen -name '*.java' > build/sources.txt
ecj -nowarn -encoding UTF-8 \
    -classpath "$ANDROID_JAR" \
    -d build/classes \
    @build/sources.txt

echo "== dx =="
mkdir -p build/dex
dx --dex --output=build/dex/classes.dex build/classes

echo "== add dex + align =="
cd build/dex
zip -q ../base.apk classes.dex
cd ../..
zipalign -f -p 4 build/base.apk build/aligned.apk

if [ ! -f "$KEYSTORE" ]; then
    echo "== create keystore =="
    keytool -genkeypair -v -keystore "$KEYSTORE" -alias redditscroll \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=RedditScroll, OU=dev, O=RedditScroll, C=US"
fi

echo "== sign =="
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android \
    --key-pass pass:android --out "$OUT" build/aligned.apk

apksigner verify "$OUT"
echo "== built: $OUT =="
ls -l "$OUT"
