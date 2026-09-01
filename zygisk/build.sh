#!/bin/bash
#
# Build the Zygisk native module for all supported ABIs
# Requires Android NDK r26+
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/.."
NDK_PATH="${NDK_PATH:-$ANDROID_NDK_HOME}"
OUTPUT_DIR="$PROJECT_DIR/zygisk/libs"

if [ -z "$NDK_PATH" ]; then
    echo "ERROR: NDK not found. Set NDK_PATH or ANDROID_NDK_HOME"
    exit 1
fi

mkdir -p "$OUTPUT_DIR"

# Download Zygisk header if not present
if [ ! -f "$SCRIPT_DIR/zygisk.h" ]; then
    echo "Downloading zygisk.h..."
    curl -sL "https://github.com/topjohnwu/zygisk-module/raw/master/zygisk.h" -o "$SCRIPT_DIR/zygisk.h"
fi

ABIS=("arm64-v8a" "armeabi-v7a" "x86_64" "x86")

for ABI in "${ABIS[@]}"; do
    echo "=== Building $ABI ==="
    BUILD_DIR="$OUTPUT_DIR/$ABI"
    mkdir -p "$BUILD_DIR"

    $NDK_PATH/toolchains/llvm/prebuilt/*/bin/clang++ \
        --target=$(get_target $ABI) \
        -shared -fPIC -fvisibility=hidden \
        -I"$SCRIPT_DIR" \
        -I"$NDK_PATH/toolchains/llvm/prebuilt/*/sysroot/usr/include" \
        -DZYGISK_API=4 \
        "$SCRIPT_DIR/src/main.cpp" \
        -o "$BUILD_DIR/libbootloaderspoofer.so" \
        -static -lz -llog

    echo "Built: $BUILD_DIR/libbootloaderspoofer.so"
done

echo "=== All ABIs built ==="
