#!/usr/bin/env bash
# Build script for Android Caster
# Requires: kotlinc, dx, aapt2, apksigner, zipalign, keytool

set -e

KOTLIN_STDLIB="/usr/share/java/kotlin-stdlib-1.3.31.jar"
KOTLIN_STDLIB_JDK7="/usr/share/java/kotlin-stdlib-jdk7-1.3.31.jar"
ANDROID_SDK="/usr/lib/android-sdk"
ANDROID_JAR="$ANDROID_SDK/platforms/android-23/android.jar"
BUILD_TOOLS="$ANDROID_SDK/build-tools/29.0.3"
DX="$ANDROID_SDK/build-tools/debian/dx"
AAPT2="$BUILD_TOOLS/aapt2"
APKSIGNER="$BUILD_TOOLS/apksigner"
ZIPALIGN="$BUILD_TOOLS/zipalign"

APP_DIR="app"
SRC_DIR="$APP_DIR/src/main/java"
RES_DIR="$APP_DIR/src/main/res"
MANIFEST="$APP_DIR/AndroidManifest.xml"
BUILD_DIR="build"
KEYSTORE="debug.keystore"

echo "=== Android Caster Build ==="
echo ""

# Clean
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/res" "$BUILD_DIR/apk"

# --- Step 1: Compile Kotlin sources ---
echo "[1/6] Compiling Kotlin sources..."
KOTLIN_SOURCES=$(find "$SRC_DIR" -name "*.kt" | tr '\n' ' ')
kotlinc \
    -classpath "$ANDROID_JAR" \
    $KOTLIN_SOURCES \
    -d "$BUILD_DIR/app-classes.jar" \
    2>&1 | grep -v "^OpenJDK\|^info:\|^warning: This build\|is bundled" || true

if [ ! -f "$BUILD_DIR/app-classes.jar" ]; then
    echo "ERROR: Kotlin compilation failed"
    exit 1
fi
echo "   -> $BUILD_DIR/app-classes.jar"

# --- Step 2: Convert to DEX (include Kotlin stdlib) ---
echo "[2/6] Converting to DEX..."
$DX --dex \
    --output="$BUILD_DIR/dex/classes.dex" \
    "$BUILD_DIR/app-classes.jar" \
    "$KOTLIN_STDLIB" \
    "$KOTLIN_STDLIB_JDK7" \
    2>&1 | grep -v "^$\|PARSE ERROR\|trouble writing" || true
echo "   -> $BUILD_DIR/dex/classes.dex"

# --- Step 3: Compile resources ---
echo "[3/6] Compiling resources..."
# Compile each resource file
find "$RES_DIR" -type f \( -name "*.xml" -o -name "*.png" -o -name "*.jpg" \) | \
    while read f; do
        $AAPT2 compile "$f" --output-to "$BUILD_DIR/res/" 2>/dev/null || \
        $AAPT2 compile "$f" -o "$BUILD_DIR/res/" 2>/dev/null || true
    done
echo "   -> $BUILD_DIR/res/"

# --- Step 4: Link resources ---
echo "[4/6] Linking resources..."
$AAPT2 link \
    --manifest "$MANIFEST" \
    -I "$ANDROID_JAR" \
    $(find "$BUILD_DIR/res" -name "*.flat" | sed 's/^/-R /' | tr '\n' ' ') \
    --java "$BUILD_DIR/classes" \
    -o "$BUILD_DIR/apk/resources.apk" \
    --auto-add-overlay \
    --min-sdk-version 23 \
    --target-sdk-version 23 \
    --version-code 1 \
    --version-name "1.0" \
    2>&1 | grep -v "^$" || true

if [ ! -f "$BUILD_DIR/apk/resources.apk" ]; then
    echo "ERROR: Resource linking failed"
    exit 1
fi
echo "   -> $BUILD_DIR/apk/resources.apk"

# --- Step 5: Assemble APK ---
echo "[5/6] Assembling APK..."
cp "$BUILD_DIR/apk/resources.apk" "$BUILD_DIR/apk/app-unsigned.apk"
cd "$BUILD_DIR/dex" && zip -u "../apk/app-unsigned.apk" classes.dex && cd ../..

# Align
$ZIPALIGN -f -v 4 "$BUILD_DIR/apk/app-unsigned.apk" "$BUILD_DIR/apk/app-aligned.apk" > /dev/null

# --- Step 6: Sign ---
echo "[6/6] Signing APK..."
if [ ! -f "$KEYSTORE" ]; then
    echo "   Generating debug keystore..."
    keytool -genkey -v \
        -keystore "$KEYSTORE" \
        -alias android \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass android \
        -keypass android \
        -dname "CN=Android Debug, O=Android, C=US" 2>/dev/null
fi

$APKSIGNER sign \
    --ks "$KEYSTORE" \
    --ks-key-alias android \
    --ks-pass pass:android \
    --key-pass pass:android \
    --min-sdk-version 23 \
    --out "android-caster.apk" \
    "$BUILD_DIR/apk/app-aligned.apk"

echo ""
echo "=== Build successful! ==="
echo "APK: android-caster.apk ($(du -sh android-caster.apk | cut -f1))"
echo ""
echo "Install on device:"
echo "  adb install android-caster.apk"
