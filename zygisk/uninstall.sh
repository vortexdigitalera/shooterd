#!/system/bin/sh
#
# Bootloader Spoofer Zygisk uninstall script
# Cleans up the NeoZygisk ptrace injection work directory
#

TMP_PATH="/dev/zygisk_bs"

# Kill any running ptrace monitors
pkill -f "zygisk-ptrace.*monitor" 2>/dev/null

# Clean up the work directory
rm -rf "$TMP_PATH"
