#!/system/bin/sh
#
# Bootloader Spoofer Zygisk post-fs-data script
# Sets up the NeoZygisk ptrace injection environment
#
# Based on NeoZygisk by JingMatrix (https://github.com/JingMatrix/NeoZygisk)
#

MODDIR=${0%/*}

# If standard Zygisk is already enabled (Magisk), don't run ptrace injection
if [ "$ZYGISK_ENABLED" ]; then
    exit 0
fi

cd "$MODDIR"

# Work directory for temporary files
TMP_PATH="/dev/zygisk_bs"

# Clean up any previous state
if [ -d "$TMP_PATH" ]; then
    rm -rf "$TMP_PATH"
fi

# Create the work directory with proper permissions
create_sys_perm() {
    mkdir -p "$1"
    chmod 555 "$1"
    chcon u:object_r:system_file:s0 "$1" 2>/dev/null
}

create_sys_perm "$TMP_PATH"

# Copy the Zygisk module library to the work directory
# The ptrace injector will load it from here
ARCH=$(getprop ro.product.cpu.abi)
case "$ARCH" in
    arm64-v8a)
        LIB_SUBDIR="lib64"
        PTRACE_BIN="zygisk-ptrace64"
        ;;
    armeabi-v7a)
        LIB_SUBDIR="lib"
        PTRACE_BIN="zygisk-ptrace32"
        ;;
    x86_64)
        LIB_SUBDIR="lib64"
        PTRACE_BIN="zygisk-ptrace64"
        ;;
    x86)
        LIB_SUBDIR="lib"
        PTRACE_BIN="zygisk-ptrace32"
        ;;
    *)
        exit 0
        ;;
esac

# Copy the module library to the work directory
if [ -f "$MODDIR/zygisk/$ARCH.so" ]; then
    create_sys_perm "$TMP_PATH/$LIB_SUBDIR"
    cp "$MODDIR/zygisk/$ARCH.so" "$TMP_PATH/$LIB_SUBDIR/libbootloaderspoofer.so"
    chcon u:object_r:system_file:s0 "$TMP_PATH/$LIB_SUBDIR/libbootloaderspoofer.so" 2>/dev/null
fi

# Start the ptrace monitor if the binary exists
if [ -f "$MODDIR/bin/$PTRACE_BIN" ]; then
    "$MODDIR/bin/$PTRACE_BIN" monitor &
fi
