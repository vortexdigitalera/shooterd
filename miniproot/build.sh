#!/bin/bash
#
# Build Mini Proot native binaries (su + prootd) for all ABIs
# Requires Android NDK r26+
#
# Outputs:
#   libs/<abi>/su       - su binary replacement
#   libs/<abi>/prootd   - root daemon
#
# These are packaged into the Magisk/APatch/KernelSU module zip.
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/.."
NDK_PATH="${NDK_PATH:-$ANDROID_NDK_HOME}"
OUTPUT_DIR="$PROJECT_DIR/miniproot/libs"

if [ -z "$NDK_PATH" ]; then
    echo "ERROR: NDK not found. Set NDK_PATH or ANDROID_NDK_HOME"
    exit 1
fi

mkdir -p "$OUTPUT_DIR"

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
    PREBUILT_DIR=$(ls -d "$NDK_PATH/toolchains/llvm/prebuilt/"*/ 2>/dev/null | head -1)
    PREBUILT_DIR="${PREBUILT_DIR%/}"
    CLANGXX="$PREBUILT_DIR/bin/clang++"
    SYSROOT="$PREBUILT_DIR/sysroot"
    API_LEVEL=26

    # 1. Build su binary (statically linked for portability)
    $CLANGXX \
        --target="$TARGET$API_LEVEL" \
        -static \
        -O2 \
        -I"$SCRIPT_DIR/src" \
        --sysroot="$SYSROOT" \
        "$SCRIPT_DIR/src/su.cpp" \
        -o "$BUILD_DIR/su" \
        -ldl

    echo "Built: $BUILD_DIR/su"

    # 2. Build prootd daemon (statically linked)
    $CLANGXX \
        --target="$TARGET$API_LEVEL" \
        -static \
        -O2 \
        -I"$SCRIPT_DIR/src" \
        --sysroot="$SYSROOT" \
        "$SCRIPT_DIR/src/prootd.cpp" \
        -o "$BUILD_DIR/prootd" \
        -ldl

    echo "Built: $BUILD_DIR/prootd"
done

echo "=== All ABIs built ==="
echo ""
echo "Output structure:"
echo "  libs/<abi>/su       - su binary replacement"
echo "  libs/<abi>/prootd   - root daemon"
echo ""
echo "These are packaged into the Magisk/APatch/KernelSU module zip."
