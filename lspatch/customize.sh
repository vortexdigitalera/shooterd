#!/system/bin/sh
#
# Bootloader Spoofer LSPatch module installer
#
# This script installs the Bootloader Spoofer module for use with
# LSPatch Manager (https://github.com/LSPosed/LSPatch).
#
# LSPatch is a non-root Xposed framework that patches APK files
# to inject Xposed modules. This installer creates the proper
# directory structure for LSPatch to discover and load the module.
#

SKIPUNZIP=0

ui_print "**********************************"
ui_print " Bootloader Spoofer LSPatch Module"
ui_print "**********************************"

# Check if running in boot mode (from manager)
if [ "$BOOTMODE" ]; then
    ui_print "- Installing from LSPatch Manager"
else
    ui_print "- Installing from recovery"
fi

# Check Android version
if [ "$API" -lt 26 ]; then
    abort "! Bootloader Spoofer requires Android 8.0 (API 26) or later"
fi
ui_print "- Device SDK: $API"

# Extract module files
ui_print "- Extracting module files"
unzip -o "$ZIPFILE" "module.prop" -d "$MODPATH" >&2
unzip -o "$ZIPFILE" "java_init.list" -d "$MODPATH" >&2

# Create the module APK directory structure
# LSPatch expects the module APK in a specific location
mkdir -p "$MODPATH"

# Extract the module APK from the zip
# The APK should be included as "module.apk" in the zip
if unzip -l "$ZIPFILE" | grep -q "module.apk"; then
    ui_print "- Extracting module APK"
    unzip -o "$ZIPFILE" "module.apk" -d "$MODPATH" >&2
else
    ui_print "! Warning: module.apk not found in zip"
    ui_print "! The module APK must be built separately and included"
fi

# Set permissions
set_perm_recursive "$MODPATH" 0 0 0755 0644

# Write boot state config for the Zygisk companion
mkdir -p /data/adb/bootloaderspoofer
BOOTSTATE="locked"
if [ -f /data/adb/bootloaderspoofer/bootstate.txt ]; then
    BOOTSTATE=$(cat /data/adb/bootloaderspoofer/bootstate.txt)
fi
echo "$BOOTSTATE" > /data/adb/bootloaderspoofer/zygisk_bootstate.txt
ui_print "- Boot state: $BOOTSTATE"

ui_print ""
ui_print "- Installation complete"
ui_print "- Open LSPatch Manager to enable this module"
ui_print "- Select target apps to patch (GMS, Play Services, etc.)"
ui_print "- Set boot state and Zygisk mode in Bootloader Spoofer app"
