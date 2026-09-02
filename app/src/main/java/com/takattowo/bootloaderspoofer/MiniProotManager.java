package com.takattowo.bootloaderspoofer;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mini Proot Manager — manages the built-in root provider.
 *
 * Mini Proot is a lightweight su replacement that provides root access
 * to apps without requiring Magisk/APatch/KernelSU's su binary. It consists
 * of a native daemon (prootd) that runs as root and a su binary that
 * connects to it via a Unix socket.
 *
 * This manager handles:
 * - Checking if prootd is running
 * - Reading/writing the UID whitelist
 * - Starting/stopping the daemon (via Shizuku or existing root)
 * - Listing apps that have root access
 */
final class MiniProotManager {

    private static final String TAG = "BootloaderSpoofer-MiniProot";

    static final String PROOTD_SOCKET = "/dev/prootd";
    static final String PROOTD_DIR = "/data/adb/bootloaderspoofer";
    static final String WHITELIST_FILE = PROOTD_DIR + "/proot_whitelist.txt";
    static final String PROOTD_LOG = PROOTD_DIR + "/prootd.log";
    static final String PROOTD_PID_FILE = PROOTD_DIR + "/prootd.pid";

    /** Check if the prootd socket exists (daemon is running). */
    static boolean isDaemonRunning() {
        return new File(PROOTD_SOCKET).exists();
    }

    /** Check if Mini Proot is installed (su binary exists). */
    static boolean isInstalled() {
        return new File("/system/bin/su").exists()
                || new File("/system/xbin/su").exists()
                || new File("/sbin/su").exists();
    }

    /**
     * Read the whitelist of allowed UIDs.
     * @return list of UIDs, or empty list if "all" is set or file not found.
     */
    static List<Integer> getWhitelistedUids() {
        List<Integer> uids = new ArrayList<>();
        File f = new File(WHITELIST_FILE);
        if (!f.exists()) return uids;

        try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.equals("all")) {
                    uids.clear();
                    uids.add(-1); // -1 means "all"
                    return uids;
                }
                try {
                    uids.add(Integer.parseInt(line));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to read whitelist", e);
        }
        return uids;
    }

    /** Check if whitelist allows all apps. */
    static boolean isAllowAll() {
        List<Integer> uids = getWhitelistedUids();
        return uids.contains(-1);
    }

    /**
     * Write the whitelist file.
     * @param uids list of UIDs to allow, or null for "all"
     */
    static boolean setWhitelist(List<Integer> uids) {
        File f = new File(WHITELIST_FILE);
        File dir = f.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }

        try (OutputStream os = new FileOutputStream(f)) {
            os.write("# Mini Proot whitelist\n".getBytes());
            os.write("# Add UIDs allowed to use su, one per line\n".getBytes());
            os.write("# Use 'all' to allow all apps\n\n".getBytes());

            if (uids == null) {
                os.write("all\n".getBytes());
            } else {
                for (int uid : uids) {
                    os.write((uid + "\n").getBytes());
                }
            }
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Failed to write whitelist", e);
            return false;
        }
    }

    /** Add a UID to the whitelist. */
    static boolean addUid(int uid) {
        List<Integer> uids = getWhitelistedUids();
        if (uids.contains(-1)) return true; // already allow all
        if (!uids.contains(uid)) {
            uids.add(uid);
        }
        return setWhitelist(uids);
    }

    /** Remove a UID from the whitelist. */
    static boolean removeUid(int uid) {
        List<Integer> uids = getWhitelistedUids();
        if (uids.contains(-1)) return true; // allow all, can't remove
        uids.remove(Integer.valueOf(uid));
        return setWhitelist(uids);
    }

    /** Set whitelist to allow all apps. */
    static boolean setAllowAll() {
        return setWhitelist(null);
    }

    /**
     * Get list of installed apps with their UIDs for the whitelist UI.
     */
    static List<AppInfo> getInstalledApps(Context ctx) {
        List<AppInfo> apps = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        List<android.content.pm.ApplicationInfo> installed =
                pm.getInstalledApplications(PackageManager.GET_META_DATA);

        List<Integer> whitelisted = getWhitelistedUids();
        boolean allowAll = whitelisted.contains(-1);

        for (android.content.pm.ApplicationInfo ai : installed) {
            if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                continue; // Skip system apps
            }
            String name = pm.getApplicationLabel(ai).toString();
            apps.add(new AppInfo(ai.packageName, name, ai.uid, allowAll || whitelisted.contains(ai.uid)));
        }

        // Sort by name
        apps.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return apps;
    }

    /** App info for the whitelist UI. */
    static class AppInfo {
        final String packageName;
        final String name;
        final int uid;
        final boolean allowed;

        AppInfo(String packageName, String name, int uid, boolean allowed) {
            this.packageName = packageName;
            this.name = name;
            this.uid = uid;
            this.allowed = allowed;
        }
    }

    /**
     * Start the prootd daemon using Shizuku shell or existing root.
     * @return true if started successfully
     */
    static boolean startDaemon() {
        if (isDaemonRunning()) {
            Log.i(TAG, "prootd already running");
            return true;
        }

        // Try via Shizuku first
        if (ShizukuManager.isConnected()) {
            String result = ShizukuManager.executeShell("prootd 2>&1 &");
            if (result != null) {
                // Wait for socket
                for (int i = 0; i < 5; i++) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    if (isDaemonRunning()) return true;
                }
            }
        }

        // Try via su (existing root)
        String suResult = ShizukuManager.executeShell("su -c 'prootd 2>&1 &' 2>/dev/null");
        if (suResult != null) {
            for (int i = 0; i < 5; i++) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                if (isDaemonRunning()) return true;
            }
        }

        Log.w(TAG, "Failed to start prootd (no root or Shizuku available)");
        return false;
    }

    /** Stop the prootd daemon. */
    static boolean stopDaemon() {
        if (!isDaemonRunning()) return true;

        // Try via Shizuku
        if (ShizukuManager.isConnected()) {
            String pid = ShizukuManager.executeShell("cat " + PROOTD_PID_FILE);
            if (pid != null && !pid.isEmpty()) {
                ShizukuManager.executeShell("kill " + pid.trim());
            }
        }

        // Try via su
        ShizukuManager.executeShell("su -c 'kill $(cat " + PROOTD_PID_FILE + ")' 2>/dev/null");

        // Wait for socket to disappear
        for (int i = 0; i < 5; i++) {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            if (!isDaemonRunning()) return true;
        }
        return false;
    }

    /** Get the prootd log (last N lines). */
    static String getDaemonLog() {
        File f = new File(PROOTD_LOG);
        if (!f.exists()) return "No log file found";

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (IOException e) {
            return "Error reading log: " + e.getMessage();
        }

        // Return last 100 lines
        String[] lines = sb.toString().split("\n");
        int start = Math.max(0, lines.length - 100);
        StringBuilder result = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            result.append(lines[i]).append("\n");
        }
        return result.toString();
    }

    /** Test root access by running a simple command via the su binary. */
    static String testRoot() {
        if (!isInstalled()) {
            return "Mini Proot su binary not installed";
        }
        if (!isDaemonRunning()) {
            return "prootd daemon not running";
        }
        // Try running 'su -c id' via Shizuku shell
        String result = ShizukuManager.executeShell("su -c id 2>&1");
        if (result != null && result.contains("uid=0")) {
            return "Root access working: " + result.trim();
        }
        return "Root test failed: " + (result != null ? result.trim() : "no output");
    }

    private MiniProotManager() {}
}
