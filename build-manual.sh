#!/bin/bash
# Manual APK build (no Gradle): aapt2 + kotlinc + d8 + apksigner.
set -e
source ~/workspace/tools/android/env.sh
PROJ=~/workspace/filesmanager-app
SDK=$ANDROID_HOME
BT=$SDK/build-tools/36.0.0
AJAR=$SDK/platforms/android-36/android.jar
B=$PROJ/build-manual
DEPS=$PROJ/manual-deps
rm -rf $B; mkdir -p $B/aars $B/flats $B/gen $B/classes $B/rclasses $B/dex

# 1) Extract AARs
for a in $DEPS/*.aar; do
  n=$(basename $a .aar)
  mkdir -p $B/aars/$n
  unzip -q -o $a -d $B/aars/$n
done
ls $B/aars/*/classes.jar > $B/libjars.txt
find $B/aars -path '*/libs/*.jar' >> $B/libjars.txt
for j in $DEPS/*.jar; do
  case "$j" in *compiler-embeddable*|*trove4j*) ;; *) echo $j >> $B/libjars.txt;; esac
done

# 2) aapt2 compile resources (app + libraries that have res files)
$BT/aapt2 compile --dir $PROJ/app/src/main/res -o $B/flats/app.zip
LIBRES=""
EXTRAPKGS=""
# Auto-derive each library's R package from its own AAR manifest (hand-maintained
# maps missed e.g. lifecycle-runtime-android -> androidx.lifecycle.runtime, whose
# missing R$id class crashed ComponentActivity.setContentView at launch).
declare -A SEENPKG=()
for d in $B/aars/*/; do
  n=$(basename $d)
  if [ -d "$d/res" ] && [ -n "$(find $d/res -type f | head -1)" ]; then
    pkg=$(grep -o 'package="[^"]*"' $d/AndroidManifest.xml | head -1 | sed 's/package="//; s/"//')
    if [ -n "$pkg" ]; then
      $BT/aapt2 compile --dir $d/res -o $B/flats/$n.zip
      LIBRES="$LIBRES $B/flats/$n.zip"
      if [ -z "${SEENPKG[$pkg]:-}" ]; then
        SEENPKG[$pkg]=1
        EXTRAPKGS="$EXTRAPKGS:$pkg"
      fi
    fi
  fi
done
EXTRAPKGS=${EXTRAPKGS#:}
echo "extra packages: $EXTRAPKGS"

# 3) Processed manifest (aapt2 needs package + version + uses-sdk in the manifest)
sed -e 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.rui.filesmanager" android:versionCode="15" android:versionName="0.2.4">|' \
    -e 's|<application|<uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36" /><application|' \
    $PROJ/app/src/main/AndroidManifest.xml > $B/AndroidManifest.xml

# 4) aapt2 link
$BT/aapt2 link -I $AJAR --manifest $B/AndroidManifest.xml \
  --java $B/gen --extra-packages "$EXTRAPKGS" \
  --auto-add-overlay -o $B/unsigned.apk $B/flats/app.zip $LIBRES
echo "LINK OK"

# 5) Compile R classes
find $B/gen -name "*.java" > $B/rjava.txt
javac -cp $AJAR -d $B/rclasses @$B/rjava.txt
echo "R OK"

# 6) Kotlin compile
CP=$B/rclasses:$(paste -sd: $B/libjars.txt):$AJAR
find $PROJ/app/src/main/java -name "*.kt" > $B/ktsrc.txt
$JAVA_HOME/bin/java -cp $DEPS/kotlin-compiler-embeddable-2.0.21.jar:$DEPS/kotlin-stdlib-2.0.21.jar:$DEPS/kotlinx-coroutines-core-jvm-1.9.0.jar:$DEPS/trove4j-1.0.20200330.jar:$DEPS/annotations-13.0.jar \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -classpath $CP \
  -Xplugin=$DEPS/kotlin-compose-compiler-plugin-embeddable-2.0.21.jar \
  -d $B/classes @$B/ktsrc.txt
echo "KOTLIN OK"

# 7) Dex everything (this d8 build rejects raw directories, so jar them first)
$JAVA_HOME/bin/jar cf $B/appclasses.jar -C $B/classes . 2>/dev/null || (cd $B/classes && zip -q -r $B/appclasses.jar .)
$JAVA_HOME/bin/jar cf $B/rclasses.jar -C $B/rclasses . 2>/dev/null || (cd $B/rclasses && zip -q -r $B/rclasses.jar .)
INPUTS="$B/appclasses.jar $B/rclasses.jar $(paste -sd' ' $B/libjars.txt)"
$BT/d8 --min-api 29 --lib $AJAR --output $B/dex $INPUTS
echo "D8 OK"

# 8) Package: add dex + native libs, align, sign
cd $B
cp unsigned.apk app.apk
for d in dex/classes*.dex; do zip -q app.apk -j $d; done
if [ -d $PROJ/app/src/main/assets ]; then (cd $PROJ/app/src/main && zip -q -r $B/app.apk assets); fi
for so in $(find aars -path "*jni/arm64-v8a/*.so" | sort -u); do
  base=$(basename $so)
  mkdir -p lib/arm64-v8a
  cp $so lib/arm64-v8a/$base
  zip -q app.apk lib/arm64-v8a/$base
done
$BT/zipalign -f -p 4 app.apk app-aligned.apk
if [ ! -f $PROJ/debug.keystore ]; then
  keytool -genkeypair -keystore $PROJ/debug.keystore -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
$BT/apksigner sign --ks $PROJ/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out $PROJ/app-manual-debug.apk app-aligned.apk
$BT/apksigner verify $PROJ/app-manual-debug.apk
ls -la $PROJ/app-manual-debug.apk
echo "BUILD DONE"
