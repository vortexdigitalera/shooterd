#!/system/bin/sh
#
# Mini Proot - service.sh
# Starts the prootd daemon on boot
#
# This script is executed by Magisk/APatch/KernelSU after boot completes.
# It starts the prootd root daemon which listens for su requests.
#

PROOTD_DIR="/data/adb/bootloaderspoofer"
PROOTD_BIN="$MODPATH/system/bin/prootd"
PROOTD_SOCKET="/dev/prootd"
PROOTD_LOG="$PROOTD_DIR/prootd.log"

# Wait for boot to complete
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
done

sleep 2  # Give system a moment to settle

# Start prootd daemon
if [ -x "$PROOTD_BIN" ]; then
    echo "[MiniProot] Starting prootd..." >> "$PROOTD_LOG"

    # Kill any existing instance
    if [ -f "$PROOTD_DIR/prootd.pid" ]; then
        OLD_PID=$(cat "$PROOTD_DIR/prootd.pid")
        if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
            echo "[MiniProot] Killing existing prootd (pid=$OLD_PID)" >> "$PROOTD_LOG"
            kill "$OLD_PID" 2>/dev/null
            sleep 1
        fi
    fi

    # Start daemon in background
    "$PROOTD_BIN" &

    # Wait for socket
    for i in 1 2 3 4 5; do
        if [ -S "$PROOTD_SOCKET" ]; then
            echo "[MiniProot] prootd started successfully" >> "$PROOTD_LOG"
            break
        fi
        sleep 1
    done

    if [ ! -S "$PROOTD_SOCKET" ]; then
        echo "[MiniProot] WARNING: prootd socket not found after 5 seconds" >> "$PROOTD_LOG"
    fi
else
    echo "[MiniProot] ERROR: prootd binary not found at $PROOTD_BIN" >> "$PROOTD_LOG"
fi
