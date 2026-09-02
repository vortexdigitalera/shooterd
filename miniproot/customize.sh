#!/system/bin/sh
#
# Mini Proot - customize.sh
# Installation script for Magisk/APatch/KernelSU
#
# This script installs the su binary and prootd daemon for the
# Bootloader Spoofer Mini Proot module.
#

SKIPUNZIP=0
PROOTD_DIR="/data/adb/bootloaderspoofer"

ui_print "==============================="
ui_print " Mini Proot Installer"
ui_print " Bootloader Spoofer"
ui_print "==============================="
ui_print ""

# Detect ABI
ARCH=$(getprop ro.product.cpu.abi)
ui_print "- Detected ABI: $ARCH"

# Map ABI to module directory
case "$ARCH" in
    arm64-v8a)
        ABI_DIR="arm64-v8a"
        ;;
    armeabi-v7a)
        ABI_DIR="armeabi-v7a"
        ;;
    x86_64)
        ABI_DIR="x86_64"
        ;;
    x86)
        ABI_DIR="x86"
        ;;
    *)
        ui_print "! Unsupported ABI: $ARCH"
        abort "! Mini Proot requires arm64-v8a, armeabi-v7a, x86_64, or x86"
        ;;
esac

ui_print "- Using binaries for: $ABI_DIR"

# Create data directory
mkdir -p "$PROOTD_DIR"
ui_print "- Created data directory: $PROOTD_DIR"

# Extract and install su binary
ui_print "- Installing su binary..."
unzip -o "$ZIPFILE" "libs/$ABI_DIR/su" -d "$MODPATH"
cp "$MODPATH/libs/$ABI_DIR/su" "$MODPATH/system/bin/su"
chmod 0755 "$MODPATH/system/bin/su"

# Extract and install prootd daemon
ui_print "- Installing prootd daemon..."
unzip -o "$ZIPFILE" "libs/$ABI_DIR/prootd" -d "$MODPATH"
cp "$MODPATH/libs/$ABI_DIR/prootd" "$MODPATH/system/bin/prootd"
chmod 0755 "$MODPATH/system/bin/prootd"

# Create whitelist if it doesn't exist
if [ ! -f "$PROOTD_DIR/proot_whitelist.txt" ]; then
    ui_print "- Creating default whitelist..."
    cat > "$PROOTD_DIR/proot_whitelist.txt" << 'EOF'
# Mini Proot whitelist
# Add UIDs allowed to use su, one per line
# Use 'all' to allow all apps (not recommended)
# Examples:
# 10001  # app with uid 10001
# all   # allow all apps
EOF
    chmod 0644 "$PROOTD_DIR/proot_whitelist.txt"
fi

# Set up permissions
ui_print "- Setting permissions..."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/bin/su" 0 0 0755
set_perm "$MODPATH/system/bin/prootd" 0 0 0755

ui_print ""
ui_print "- Mini Proot installed successfully!"
ui_print "- prootd will start on boot via service.sh"
ui_print "- Configure whitelist at:"
ui_print "  $PROOTD_DIR/proot_whitelist.txt"
ui_print ""
ui_print " To allow all apps: echo 'all' > $PROOTD_DIR/proot_whitelist.txt"
ui_print " To allow specific app: add its UID to the whitelist"
ui_print ""
