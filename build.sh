#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if ! command -v javac >/dev/null; then
    export JAVA_HOME="${JAVA_HOME:-/home/chunxiaobian/Downloads/android-studio/jbr}"
    export PATH="$JAVA_HOME/bin:$PATH"
fi
SDK="${ANDROID_SDK_ROOT:-/home/chunxiaobian/Android/Sdk}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-37.0/android.jar"
mkdir -p build/classes build/dex output .signing
"$BT/aapt2" compile --dir app/src/main/res -o build/resources.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest app/src/main/AndroidManifest.xml -A vendor/hyperlpr3/assets build/resources.zip
find app/src/main/java vendor/hyperlpr3/java -name '*.java' > build/sources.txt
javac -source 8 -target 8 -encoding UTF-8 -bootclasspath "$BT/core-lambda-stubs.jar:$JAR" -d build/classes @build/sources.txt
jar cf build/classes.jar -C build/classes .
"$BT/d8" --min-api 26 --lib "$JAR" --output build/dex build/classes.jar
python3 - <<'PY'
from pathlib import Path
import zipfile, shutil
shutil.copy('build/base.apk','build/unsigned.apk')
with zipfile.ZipFile('build/unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as z:
    for f in Path('build/dex').glob('*.dex'): z.write(f,f.name)
    for f in Path('vendor/hyperlpr3/libs').glob('*/*.so'): z.write(f,'lib/'+str(f.relative_to('vendor/hyperlpr3/libs')))
PY
if [ ! -f .signing/local.jks ]; then
    keytool -genkeypair -keystore .signing/local.jks -storepass android -keypass android -alias local -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Plate Journal Local Build' >/dev/null 2>&1
fi
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks .signing/local.jks --ks-pass pass:android --key-pass pass:android --ks-key-alias local --out output/PlateJournal-1.7.apk build/aligned.apk
"$BT/apksigner" verify --verbose output/PlateJournal-1.7.apk
"$BT/aapt" dump badging output/PlateJournal-1.7.apk | head -12
sha256sum output/PlateJournal-1.7.apk > output/SHA256SUMS.txt
