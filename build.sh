#!/bin/sh
# Builds build/rxVision.apk with the Android SDK command-line tools (no Gradle).
# Install: adb install -r build/rxVision.apk
set -e
cd "$(dirname "$0")"

SDK=$HOME/Android/Sdk
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
export JAVA_HOME=$HOME/.local/opt/jdk17
export PATH=$JAVA_HOME/bin:$PATH

rm -rf build/apk
mkdir -p build/apk/gen build/apk/classes build/apk/dex

"$BT/aapt2" compile --dir res -o build/apk/res.zip
# Version = commits so far, like the other rxenv apps.
VERSION=$(git rev-list --count HEAD 2>/dev/null || echo 1)
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --java build/apk/gen \
    --version-code "$VERSION" --version-name "$VERSION" --replace-version \
    --min-sdk-version 33 --target-sdk-version 35 -A assets -o build/apk/unsigned.apk build/apk/res.zip

javac --release 11 -classpath "$JAR" -Xlint:-options -d build/apk/classes \
    $(find src build/apk/gen -name '*.java')
"$BT/d8" --min-api 33 --lib "$JAR" --output build/apk/dex $(find build/apk/classes -name '*.class')

cp build/apk/unsigned.apk build/apk/app.apk
(cd build/apk/dex && zip -q ../app.apk classes.dex)
"$BT/zipalign" -f 4 build/apk/app.apk build/apk/aligned.apk

[ -f signing.keystore ] || keytool -genkeypair -keystore signing.keystore -storepass android \
    -keypass android -alias key -keyalg RSA -keysize 2048 -validity 10000 -dname CN=rxVision
"$BT/apksigner" sign --ks signing.keystore --ks-pass pass:android --out build/rxVision.apk build/apk/aligned.apk
echo "Built build/rxVision.apk"
