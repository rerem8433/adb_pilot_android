#!/bin/bash
# Compile ADB Pilot Android (sans Gradle) : javac + dx + aapt + apksigner
set -e
unset JAVA_TOOL_OPTIONS
cd "$(dirname "$0")"
SDK=/home/claude/android/sdk/android-30.jar
BT=/usr/lib/android-sdk/build-tools/debian
OUT=build
rm -rf $OUT && mkdir -p $OUT/classes $OUT/helper $OUT/gen
VER=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)

echo "== helper (dex exécuté sur l'appareil cible)"
javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath $SDK -cp $SDK -d $OUT/helper $(find helper/src -name '*.java') 2>&1 | grep -v "warning\|^Note\|^1 warning" || true
java -jar $BT/lib/dx.jar --dex --output=$OUT/helper.dex $OUT/helper
(cd $OUT && mkdir -p hj && cp helper.dex hj/classes.dex && cd hj && zip -q -X ../../assets/helper.jar classes.dex)

echo "== ressources"
aapt package -f -m -J $OUT/gen -M AndroidManifest.xml -S res -I $SDK -A assets -F $OUT/res.apk --min-sdk-version 21 --target-sdk-version 29

echo "== java"
javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath $SDK -cp $SDK -d $OUT/classes $(find src $OUT/gen -name '*.java') > $OUT/javac.log 2>&1 || { grep -v "^warning: \[options\]\|^Note:" $OUT/javac.log; exit 1; }
java -jar $BT/lib/dx.jar --dex --min-sdk-version=21 --output=$OUT/classes.dex $OUT/classes

echo "== apk"
cp $OUT/res.apk $OUT/unsigned.apk
(cd $OUT && zip -q -j unsigned.apk classes.dex)
zipalign -f 4 $OUT/unsigned.apk $OUT/aligned.apk
KS=/home/claude/android/adbpilot-test.keystore
[ -f $KS ] || keytool -genkeypair -keystore $KS -storepass adbpilot -keypass adbpilot -alias adbpilot \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ParadoxXx Squad, O=ADB Pilot, C=FR" >/dev/null 2>&1
apksigner sign --ks $KS --ks-pass pass:adbpilot --key-pass pass:adbpilot --out $OUT/ADB-Pilot-$VER-android.apk $OUT/aligned.apk
apksigner verify -v $OUT/ADB-Pilot-$VER-android.apk | head -4
ls -la $OUT/ADB-Pilot-$VER-android.apk
