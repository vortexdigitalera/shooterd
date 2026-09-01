#!/system/bin/sh
#
# Bootloader Spoofer Zygisk module installer
#

SKIPUNZIP=0
API=26

if [ "$API" -lt 26 ]; then
    abort "! Bootloader Spoofer Zygisk requires Android 8.0 (API 26) or later"
fi

ui_print "- Bootloader Spoofer Zygisk module"
ui_print "- Installing native libraries..."

# Extract Zygisk libraries for the device's ABI
ARCH=$(getprop ro.product.cpu.abi)
case "$ARCH" in
    arm64-v8a)
        ui_print "- Detected arm64-v8a"
        extract_zygisk_lib "arm64-v8a"
        ;;
    armeabi-v7a)
        ui_print "- Detected armeabi-v7a"
        extract_zygisk_lib "armeabi-v7a"
        ;;
    x86_64)
        ui_print "- Detected x86_64"
        extract_zygisk_lib "x86_64"
        ;;
    x86)
        ui_print "- Detected x86"
        extract_zygisk_lib "x86"
        ;;
    *)
        abort "! Unsupported architecture: $ARCH"
        ;;
esac

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

ui_print "- Installation complete"
ui_print "- Enable Zygisk in Magisk settings"
ui_print "- Set Zygisk mode in the Bootloader Spoofer app"
