#!/system/bin/sh
#
# Bootloader Spoofer Zygisk service script
# Starts the NeoZygisk ptrace monitor as a late-start service
#
# Based on NeoZygisk by JingMatrix (https://github.com/JingMatrix/NeoZygisk)
#

MODDIR=${0%/*}

# If standard Zygisk is already enabled (Magisk), don't run ptrace injection
if [ "$ZYGISK_ENABLED" ]; then
    exit 0
fi

cd "$MODDIR"

# Determine the ptrace binary for this architecture
ARCH=$(getprop ro.product.cpu.abi)
case "$ARCH" in
    arm64-v8a|x86_64)
        PTRACE_BIN="zygisk-ptrace64"
        ;;
    armeabi-v7a|x86)
        PTRACE_BIN="zygisk-ptrace32"
        ;;
    *)
        exit 0
        ;;
esac

# If the monitor is not already running (post-fs-data.sh may have started it),
# start it now as a fallback
if [ -f "$MODDIR/bin/$PTRACE_BIN" ]; then
    # Check if monitor is already running
    if ! pgrep -f "$PTRACE_BIN monitor" >/dev/null 2>&1; then
        "$MODDIR/bin/$PTRACE_BIN" monitor &
    fi
fi
