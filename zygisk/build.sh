#!/bin/bash
#
# Build the Zygisk native module and NeoZygisk ptrace injector for all ABIs
# Requires Android NDK r26+
#
# This builds two components:
# 1. libbootloaderspoofer.so - The Zygisk module (hooks system properties)
# 2. zygisk-ptrace{32,64} - NeoZygisk-style ptrace injector binary
#
# Based on NeoZygisk by JingMatrix (https://github.com/JingMatrix/NeoZygisk)
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
    curl -sL "https://raw.githubusercontent.com/topjohnwu/zygisk-module-sample/master/module/jni/zygisk.hpp" -o "$SCRIPT_DIR/zygisk.h"
fi

get_target() {
    case "$1" in
        arm64-v8a)   echo "aarch64-linux-android" ;;
        armeabi-v7a) echo "armv7a-linux-androideabi" ;;
        x86_64)      echo "x86_64-linux-android" ;;
        x86)         echo "i686-linux-android" ;;
        *) echo "unknown" ;;
    esac
}

ABIS=("arm64-v8a" "armeabi-v7a" "x86_64" "x86")

for ABI in "${ABIS[@]}"; do
    echo "=== Building $ABI ==="
    BUILD_DIR="$OUTPUT_DIR/$ABI"
    mkdir -p "$BUILD_DIR"

    TARGET=$(get_target "$ABI")
    # Resolve the actual prebuilt directory (handle linux-x86_64 or darwin-x86_64)
    PREBUILT_DIR=$(ls -d "$NDK_PATH/toolchains/llvm/prebuilt/"*/ 2>/dev/null | head -1)
    PREBUILT_DIR="${PREBUILT_DIR%/}"
    CLANGXX="$PREBUILT_DIR/bin/clang++"
    SYSROOT="$PREBUILT_DIR/sysroot"
    API_LEVEL=26

    # 1. Build the Zygisk module library (libbootloaderspoofer.so)
    $CLANGXX \
        --target="$TARGET$API_LEVEL" \
        -shared -fPIC \
        -I"$SCRIPT_DIR" \
        -I"$SCRIPT_DIR/src" \
        --sysroot="$SYSROOT" \
        -DZYGISK_API=4 \
        "$SCRIPT_DIR/src/main.cpp" \
        -o "$BUILD_DIR/libbootloaderspoofer.so" \
        -llog -ldl

    echo "Built: $BUILD_DIR/libbootloaderspoofer.so"

    # 2. Build the NeoZygisk ptrace injector binary
    # Only build for arm64 and arm (primary Zygote ABIs)
    case "$ABI" in
        arm64-v8a)
            PTRACE_NAME="zygisk-ptrace64"
            ;;
        armeabi-v7a)
            PTRACE_NAME="zygisk-ptrace32"
            ;;
        x86_64)
            PTRACE_NAME="zygisk-ptrace64"
            ;;
        x86)
            PTRACE_NAME="zygisk-ptrace32"
            ;;
    esac

    $CLANGXX \
        --target="$TARGET$API_LEVEL" \
        -static \
        -I"$SCRIPT_DIR/src" \
        --sysroot="$SYSROOT" \
        "$SCRIPT_DIR/src/ptracer/ptrace_injector.cpp" \
        -o "$BUILD_DIR/$PTRACE_NAME" \
        -ldl

    echo "Built: $BUILD_DIR/$PTRACE_NAME"
done

echo "=== All ABIs built ==="
echo ""
echo "Output structure:"
echo "  libs/<abi>/libbootloaderspoofer.so  - Zygisk module"
echo "  libs/<abi>/zygisk-ptrace{32,64}     - NeoZygisk ptrace injector"
echo ""
echo "The ptrace injector provides Zygisk API support for APatch/KernelSU/Magisk"
echo "by injecting libbootloaderspoofer.so into the Zygote process via ptrace."
