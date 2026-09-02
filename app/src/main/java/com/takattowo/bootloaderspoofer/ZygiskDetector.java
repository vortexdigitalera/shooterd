package com.takattowo.bootloaderspoofer;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;

/**
 * Detects Zygisk availability from multiple root frameworks:
 * <ul>
 *   <li>Magisk (built-in Zygisk)</li>
 *   <li>KernelSU (built-in Zygisk)</li>
 *   <li>APatch (built-in Zygisk)</li>
 *   <li>NeoZygisk (ptrace-based, works with any root)</li>
 * </ul>
 *
 * <p>Also detects which root framework is active, so the app can
 * choose the right Zygisk integration path.
 */
final class ZygiskDetector {

    private static final String TAG = "ZygiskDetector";

    /** Root framework type. */
    enum RootFramework {
        NONE("None"),
        MAGISK("Magisk"),
        KERNELSU("KernelSU"),
        APATCH("APatch"),
        SUI("Sui");

        final String label;
        RootFramework(String label) { this.label = label; }
    }

    /** Zygisk implementation type. */
    enum ZygiskImpl {
        NONE("Not available"),
        MAGISK_BUILTIN("Magisk Zygisk"),
        KSU_BUILTIN("KernelSU Zygisk"),
        APATCH_BUILTIN("APatch Zygisk"),
        NEOZYGISK("NeoZygisk (ptrace)");

        final String label;
        ZygiskImpl(String label) { this.label = label; }
    }

    static final class DetectionResult {
        final RootFramework root;
        final ZygiskImpl zygisk;
        final boolean zygiskEnabled;
        final boolean ourModuleInstalled;
        final String magiskVersion;
        final String ksuVersion;
        final String apatchVersion;

        DetectionResult(RootFramework root, ZygiskImpl zygisk, boolean zygiskEnabled,
                        boolean ourModuleInstalled, String magiskVersion,
                        String ksuVersion, String apatchVersion) {
            this.root = root;
            this.zygisk = zygisk;
            this.zygiskEnabled = zygiskEnabled;
            this.ourModuleInstalled = ourModuleInstalled;
            this.magiskVersion = magiskVersion;
            this.ksuVersion = ksuVersion;
            this.apatchVersion = apatchVersion;
        }

        boolean hasRoot() { return root != RootFramework.NONE; }
        boolean hasZygisk() { return zygisk != ZygiskImpl.NONE; }

        String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("Root: ").append(root.label);
            if (magiskVersion != null) sb.append(" v").append(magiskVersion);
            if (ksuVersion != null) sb.append(" v").append(ksuVersion);
            if (apatchVersion != null) sb.append(" v").append(apatchVersion);
            sb.append("\nZygisk: ").append(zygisk.label);
            if (zygiskEnabled) sb.append(" (enabled)");
            sb.append("\nOur module: ").append(ourModuleInstalled ? "installed" : "not installed");
            return sb.toString();
        }
    }

    /**
     * Detect root framework and Zygisk availability.
     * Uses PackageManager to check for root apps, and filesystem checks
     * for Zygisk state files.
     */
    static DetectionResult detect(Context ctx) {
        RootFramework root = RootFramework.NONE;
        String magiskVersion = null;
        String ksuVersion = null;
        String apatchVersion = null;

        // Check for Magisk
        if (isPackageInstalled(ctx, "com.topjohnwu.magisk")) {
            root = RootFramework.MAGISK;
            magiskVersion = getPackageVersion(ctx, "com.topjohnwu.magisk");
        }

        // Check for KernelSU manager
        if (isPackageInstalled(ctx, "me.weishu.kernelsu")) {
            root = RootFramework.KERNELSU;
            ksuVersion = getPackageVersion(ctx, "me.weishu.kernelsu");
        }

        // Check for APatch
        if (isPackageInstalled(ctx, "me.bmax.apatch")) {
            root = RootFramework.APATCH;
            apatchVersion = getPackageVersion(ctx, "me.bmax.apatch");
        }

        // Check for Sui (root-based Shizuku, implies Magisk)
        if (isPackageInstalled(ctx, "moe.shizuku.sui")) {
            if (root == RootFramework.NONE) root = RootFramework.SUI;
        }

        // Detect Zygisk implementation
        ZygiskImpl zygisk = ZygiskImpl.NONE;
        boolean zygiskEnabled = false;

        // Check Magisk Zygisk: /data/adb/modules/zygisksu or magisk zygisk enabled
        if (root == RootFramework.MAGISK) {
            if (fileExists("/data/adb/zygisk") || fileExists("/data/adb/zygisksu")) {
                zygisk = ZygiskImpl.MAGISK_BUILTIN;
                zygiskEnabled = true;
            } else {
                // Check if Zygisk is enabled in Magisk settings
                zygisk = ZygiskImpl.MAGISK_BUILTIN;
                zygiskEnabled = checkMagiskZygiskEnabled();
            }
        }

        // Check KernelSU Zygisk
        if (root == RootFramework.KERNELSU) {
            // KernelSU has built-in Zygisk support
            if (fileExists("/data/adb/ksu/modules") || fileExists("/data/adb/ksu")) {
                zygisk = ZygiskImpl.KSU_BUILTIN;
                zygiskEnabled = fileExists("/data/adb/ksu/zygisk_enabled")
                        || checkKsuZygiskEnabled();
            }
        }

        // Check APatch Zygisk
        if (root == RootFramework.APATCH) {
            if (fileExists("/data/adb/ap") || fileExists("/data/adb/apatch")) {
                zygisk = ZygiskImpl.APATCH_BUILTIN;
                zygiskEnabled = fileExists("/data/adb/ap/zygisk_enabled")
                        || checkApatchZygiskEnabled();
            }
        }

        // Check NeoZygisk (our ptrace injector) — works with any root
        if (fileExists("/data/adb/modules/bootloaderspoofer_zygisk/bin/zygisk-ptrace64")
                || fileExists("/data/adb/modules/bootloaderspoofer_zygisk/bin/zygisk-ptrace32")) {
            if (zygisk == ZygiskImpl.NONE) {
                zygisk = ZygiskImpl.NEOZYGISK;
                zygiskEnabled = true;
            }
        }

        // Check if our Zygisk module is installed
        boolean ourModuleInstalled = fileExists("/data/adb/modules/bootloaderspoofer_zygisk/module.prop")
                || fileExists("/data/adb/modules/bootloaderspoofer_zygisk");

        return new DetectionResult(root, zygisk, zygiskEnabled, ourModuleInstalled,
                magiskVersion, ksuVersion, apatchVersion);
    }

    /**
     * Detect using shell commands (more reliable, requires Shizuku/root).
     */
    static DetectionResult detectWithShell(Context ctx, ShellExecutor shell) {
        DetectionResult base = detect(ctx);

        // Use shell to check more accurately
        if (shell != null) {
            // Check Magisk
            String magisk = shell.execute("magisk -V 2>/dev/null");
            if (magisk != null && !magisk.isEmpty()) {
                base = new DetectionResult(RootFramework.MAGISK,
                        base.zygisk, base.zygiskEnabled, base.ourModuleInstalled,
                        magisk.trim(), base.ksuVersion, base.apatchVersion);
            }

            // Check KernelSU
            String ksu = shell.execute("cat /data/adb/ksu/version 2>/dev/null");
            if (ksu != null && !ksu.isEmpty()) {
                base = new DetectionResult(RootFramework.KERNELSU,
                        ZygiskImpl.KSU_BUILTIN, true, base.ourModuleInstalled,
                        base.magiskVersion, ksu.trim(), base.apatchVersion);
            }

            // Check APatch
            String apatch = shell.execute("cat /data/adb/ap/version 2>/dev/null");
            if (apatch != null && !apatch.isEmpty()) {
                base = new DetectionResult(RootFramework.APATCH,
                        ZygiskImpl.APATCH_BUILTIN, true, base.ourModuleInstalled,
                        base.magiskVersion, base.ksuVersion, apatch.trim());
            }

            // Check Zygisk enabled state more accurately
            String zygiskCheck = shell.execute(
                    "for f in /data/adb/zygisk /data/adb/zygisksu /data/adb/ksu/zygisk_enabled /data/adb/ap/zygisk_enabled; do " +
                    "[ -e \"$f\" ] && echo \"$f\" && break; done 2>/dev/null");
            if (zygiskCheck != null && !zygiskCheck.isEmpty()) {
                // Zygisk is enabled
            }
        }

        return base;
    }

    interface ShellExecutor {
        String execute(String command);
    }

    // --- Private helpers ---

    private static boolean isPackageInstalled(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getPackageInfo(pkg, 0) != null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String getPackageVersion(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getPackageInfo(pkg, 0).versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean fileExists(String path) {
        try {
            return new File(path).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean checkMagiskZygiskEnabled() {
        // Magisk stores config in /data/adb/magisk.db or /cache/.magisk
        // We can't read the DB directly, but the presence of /data/adb/zygisk
        // indicates Zygisk is enabled
        return fileExists("/data/adb/zygisk") || fileExists("/data/adb/zygisksu");
    }

    private static boolean checkKsuZygiskEnabled() {
        return fileExists("/data/adb/ksu/zygisk_enabled");
    }

    private static boolean checkApatchZygiskEnabled() {
        return fileExists("/data/adb/ap/zygisk_enabled");
    }
}
