#!/system/bin/sh
#
# Mini Proot - post-fs-data.sh
# Sets up the su binary path before the system is fully booted
#

PROOTD_DIR="/data/adb/bootloaderspoofer"

# Ensure data directory exists
mkdir -p "$PROOTD_DIR"

# Create su symlink in /system/bin if not already there
# (Magisk overlays this via module system, so this is just a fallback)
if [ ! -f "/system/bin/su" ] && [ ! -L "/system/bin/su" ]; then
    # The module's system/bin/su will be overlaid by Magisk
    :
fi

# Log startup
echo "[MiniProot] post-fs-data.sh executed" >> "$PROOTD_DIR/prootd.log" 2>/dev/null
