#!/system/bin/sh
#
# Mini Proot - uninstall.sh
# Cleanup when module is removed
#

PROOTD_DIR="/data/adb/bootloaderspoofer"

# Kill prootd if running
if [ -f "$PROOTD_DIR/prootd.pid" ]; then
    PID=$(cat "$PROOTD_DIR/prootd.pid")
    if [ -n "$PID" ]; then
        kill "$PID" 2>/dev/null
    fi
fi

# Remove socket
rm -f /dev/prootd

# Remove PID file
rm -f "$PROOTD_DIR/prootd.pid"

# Note: We don't remove the whitelist or log files
# so the user's configuration is preserved
