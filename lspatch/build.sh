#!/bin/bash
#
# Build the LSPatch module zip for Bootloader Spoofer
#
# This script:
# 1. Builds the release APK
# 2. Packages it as an LSPatch-compatible zip module
#
# Usage: bash lspatch/build.sh
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/.."
OUTPUT_DIR="$SCRIPT_DIR"

echo "=== Building Bootloader Spoofer LSPatch Module ==="

# Step 1: Build the release APK
echo "--- Building release APK ---"
cd "$PROJECT_DIR"
export ANDROID_HOME=${ANDROID_HOME:-/tmp/android-sdk}
export JAVA_HOME=${JAVA_HOME:-/home/codespace/java/21.0.10-ms}
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew :app:assembleRelease

APK_FILE="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK_FILE" ]; then
    echo "ERROR: Release APK not found at $APK_FILE"
    exit 1
fi

echo "Built APK: $APK_FILE"

# Step 2: Create the LSPatch zip
echo "--- Creating LSPatch zip ---"
STAGING_DIR=$(mktemp -d)
mkdir -p "$STAGING_DIR"

# Copy module files
cp "$SCRIPT_DIR/module.prop" "$STAGING_DIR/"
cp "$SCRIPT_DIR/java_init.list" "$STAGING_DIR/"
cp "$SCRIPT_DIR/customize.sh" "$STAGING_DIR/"

# Copy the APK as module.apk
cp "$APK_FILE" "$STAGING_DIR/module.apk"

# Create the zip
ZIP_FILE="$OUTPUT_DIR/bootloaderspoofer-lspatch.zip"
cd "$STAGING_DIR"
zip -r "$ZIP_FILE" . -x ".*"
cd "$PROJECT_DIR"

# Clean up
rm -rf "$STAGING_DIR"

echo ""
echo "=== LSPatch module built ==="
echo "Output: $ZIP_FILE"
echo ""
echo "To install:"
echo "1. Transfer the zip to your device"
echo "2. Open LSPatch Manager"
echo "3. Install the module from the zip"
echo "4. Select target apps to patch (GMS, Play Services, etc.)"
echo "5. Enable the Bootloader Spoofer module"
