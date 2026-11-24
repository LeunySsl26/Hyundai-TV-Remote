#!/usr/bin/env bash
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_JAR="${ANDROID_JAR:-$HOME/Android/sdk/platforms/android-34/android.jar}"
KEYSTORE="${KEYSTORE:-$HOME/.debug.keystore}"

cd "$PROJECT_DIR"
rm -rf bin obj gen compiled_res
mkdir -p bin obj gen compiled_res

echo "1/6 Compilando recursos con aapt2..."
aapt2 compile --dir res -o compiled_res/

echo "2/6 Enlazando APK y generando R.java..."
aapt2 link -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --java gen/ \
  -o bin/base.apk \
  compiled_res/*.flat \
  --auto-add-overlay

echo "3/6 Compilando clases Java con javac..."
javac -cp "$ANDROID_JAR:libs/*" \
  -d obj/ \
  $(find src gen -name "*.java")

echo "4/6 Convirtiendo bytecode a DEX con D8..."
d8 --lib "$ANDROID_JAR" \
  --min-api 26 \
  --output bin/ \
  $(find obj -name "*.class") libs/*.jar

echo "5/6 Empaquetando classes.dex..."
cd bin
zip -u base.apk classes.dex

echo "6/6 Firmando APK con apksigner..."
apksigner sign --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out HyundaiRemote.apk \
  base.apk

echo "Verificando firma..."
apksigner verify --verbose HyundaiRemote.apk

cp HyundaiRemote.apk "$PROJECT_DIR/../HyundaiRemote.apk"
if [ -d /sdcard/Download ]; then
  cp HyundaiRemote.apk /sdcard/Download/HyundaiRemote.apk
  echo "Copia guardada en /sdcard/Download/HyundaiRemote.apk"
fi

echo "==========================================="
echo " ¡APK CONSTRUIDO CON ÉXITO: HyundaiRemote.apk!"
echo "==========================================="
