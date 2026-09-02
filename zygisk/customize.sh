#!/system/bin/sh
#
# Bootloader Spoofer Zygisk module installer
# Supports standard Zygisk (Magisk) and NeoZygisk ptrace injection (APatch/KernelSU)
#
# Based on NeoZygisk by JingMatrix (https://github.com/JingMatrix/NeoZygisk)
#

SKIPUNZIP=1
API=26

if [ "$API" -lt 26 ]; then
    abort "! Bootloader Spoofer Zygisk requires Android 8.0 (API 26) or later"
fi

ui_print "**********************************"
ui_print " Bootloader Spoofer Zygisk Module"
ui_print " With NeoZygisk ptrace injection"
ui_print "**********************************"

# Check root implementation
if [ "$BOOTMODE" ] && [ "$APATCH" ]; then
    ui_print "- Installing from APatch app"
    ui_print "- NeoZygisk ptrace injection will be used"
elif [ "$BOOTMODE" ] && [ "$KSU" ]; then
    ui_print "- Installing from KernelSU app"
    ui_print "- NeoZygisk ptrace injection will be used"
elif [ "$BOOTMODE" ] && [ "$MAGISK_VER_CODE" ]; then
    ui_print "- Installing from Magisk app"
    ui_print "- Standard Zygisk API will be used (if enabled)"
    ui_print "- NeoZygisk ptrace injection available as fallback"
else
    ui_print "- Installing from recovery"
    ui_print "- NeoZygisk ptrace injection will be used"
fi

# Check architecture
ARCH=$(getprop ro.product.cpu.abi)
case "$ARCH" in
    arm64-v8a)
        ui_print "- Detected arm64-v8a"
        PTRACE_BIN="zygisk-ptrace64"
        LIB_SUBDIR="lib64"
        ;;
    armeabi-v7a)
        ui_print "- Detected armeabi-v7a"
        PTRACE_BIN="zygisk-ptrace32"
        LIB_SUBDIR="lib"
        ;;
    x86_64)
        ui_print "- Detected x86_64"
        PTRACE_BIN="zygisk-ptrace64"
        LIB_SUBDIR="lib64"
        ;;
    x86)
        ui_print "- Detected x86"
        PTRACE_BIN="zygisk-ptrace32"
        LIB_SUBDIR="lib"
        ;;
    *)
        abort "! Unsupported architecture: $ARCH"
        ;;
esac

# Extract module files
ui_print "- Extracting module files"
[ -f "$MODPATH" ] || mkdir -p "$MODPATH"

# Extract the Zygisk library and ptrace binary from the zip
# The zip should contain libs/<arch>/libbootloaderspoofer.so and libs/<arch>/zygisk-ptrace*
ZIPFILE="$ZIPFILE"

# Create directories
mkdir -p "$MODPATH/zygisk"
mkdir -p "$MODPATH/bin"
mkdir -p "$MODPATH/$LIB_SUBDIR"

# Extract Zygisk module library
ui_print "- Extracting Zygisk module library"
unzip -o "$ZIPFILE" "libs/$ARCH/libbootloaderspoofer.so" -d "$TMPDIR" >&2
if [ -f "$TMPDIR/libs/$ARCH/libbootloaderspoofer.so" ]; then
    cp "$TMPDIR/libs/$ARCH/libbootloaderspoofer.so" "$MODPATH/zygisk/$ARCH.so"
    ui_print "- Installed Zygisk module: zygisk/$ARCH.so"
else
    ui_print "! Warning: Zygisk module library not found in zip"
    ui_print "! Standard Zygisk mode will not be available"
fi

# Extract NeoZygisk ptrace injector
ui_print "- Extracting NeoZygisk ptrace injector"
unzip -o "$ZIPFILE" "libs/$ARCH/$PTRACE_BIN" -d "$TMPDIR" >&2
if [ -f "$TMPDIR/libs/$ARCH/$PTRACE_BIN" ]; then
    cp "$TMPDIR/libs/$ARCH/$PTRACE_BIN" "$MODPATH/bin/$PTRACE_BIN"
    chmod 755 "$MODPATH/bin/$PTRACE_BIN"
    ui_print "- Installed ptrace injector: bin/$PTRACE_BIN"
else
    ui_print "! Warning: ptrace injector not found in zip"
    ui_print "! NeoZygisk ptrace injection will not be available"
fi

# Extract module scripts
unzip -o "$ZIPFILE" "module.prop" -d "$MODPATH" >&2
unzip -o "$ZIPFILE" "post-fs-data.sh" -d "$MODPATH" >&2
unzip -o "$ZIPFILE" "service.sh" -d "$MODPATH" >&2
unzip -o "$ZIPFILE" "uninstall.sh" -d "$MODPATH" >&2

# Read boot state from the LSPosed module's config if available
BOOTSTATE_FILE="/data/adb/bootloaderspoofer/bootstate.txt"
if [ -f "$BOOTSTATE_FILE" ]; then
    BOOTSTATE=$(cat "$BOOTSTATE_FILE")
    ui_print "- Boot state from config: $BOOTSTATE"
else
    BOOTSTATE="locked"
    ui_print "- No boot state config found, defaulting to: $BOOTSTATE"
fi

# Write the boot state to a file the Zygisk native lib can read
mkdir -p /data/adb/bootloaderspoofer
echo "$BOOTSTATE" > /data/adb/bootloaderspoofer/zygisk_bootstate.txt

# Set permissions
set_perm_recursive "$MODPATH/bin" 0 0 0755 0755
set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
set_perm_recursive "$MODPATH/$LIB_SUBDIR" 0 0 0755 0644 u:object_r:system_lib_file:s0

ui_print "- Installation complete"
ui_print ""
ui_print "- For Magisk: Enable Zygisk in Magisk settings"
ui_print "- For APatch/KernelSU: NeoZygisk ptrace injection starts automatically"
ui_print "- Set Zygisk mode in the Bootloader Spoofer app"
