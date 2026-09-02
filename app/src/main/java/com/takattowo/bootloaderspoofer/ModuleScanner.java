package com.takattowo.bootloaderspoofer;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Scans for installed modules from two sources:
 * <ol>
 *   <li>Magisk/KernelSU directory modules in {@code /data/adb/modules/}</li>
 *   <li>Installed Xposed module APKs (detected via META-INF/xposed/java_init.list
 *       or assets/xposed_init, like LSPatch's LSPPackageManager.isModuleApk)</li>
 * </ol>
 *
 * <p>Magisk scanning is batched into a single shell command for performance
 * (one process instead of N+1 per module), with a structured error result.
 */
final class ModuleScanner {

    private static final String TAG = "ModuleScanner";
    private static final String MODULES_DIR = "/data/adb/modules";

    /** Structured error from a scan operation. */
    static final class ScanError {
        final String operation;
        final String detail;

        ScanError(String operation, String detail) {
            this.operation = operation;
            this.detail = detail;
        }
    }

    /** Result of a module scan. */
    static final class ScanResult {
        final List<ModuleInfo> modules = new ArrayList<>();
        ScanError error;
        boolean usedRoot;

        boolean hasError() { return error != null; }
    }

    private final ShellExecutor shell;

    ModuleScanner(ShellExecutor shell) {
        this.shell = shell;
    }

    interface ShellExecutor {
        String execute(String command);
    }

    // ---- Magisk directory scan (batched) ----

    /**
     * Scan /data/adb/modules/ using a single batched shell command.
     *
     * <p>For each module directory, outputs a delimited record containing
     * the module.prop content and marker file existence — all in one shell
     * invocation instead of 3N+1 separate commands.
     */
    ScanResult scanMagiskModules() {
        ScanResult result = new ScanResult();

        // Single batched command: for each module dir, print a delimited record.
        // Format per module:
        //   ===MODULE:id===
        //   <module.prop content>
        //   ===STATE:id===
        //   disable=<0|1> remove=<0|1> update=<0|1> icon=<0|1>
        String script =
            "for d in " + MODULES_DIR + "/*/; do " +
                "id=$(basename \"$d\"); " +
                "if [ -f \"$d/module.prop\" ]; then " +
                    "echo '===MODULE:'\"$id\"'==='; " +
                    "cat \"$d/module.prop\"; " +
                    "echo ''; " +
                    "echo '===STATE:'\"$id\"'==='; " +
                    "[ -f \"$d/disable\" ] && echo -n 'disable=1 ' || echo -n 'disable=0 '; " +
                    "[ -f \"$d/remove\" ] && echo -n 'remove=1 ' || echo -n 'remove=0 '; " +
                    "[ -f \"$d/update\" ] && echo -n 'update=1 ' || echo -n 'update=0 '; " +
                    "[ -f \"$d/icon.png\" ] && echo 'icon=1' || echo 'icon=0'; " +
                "fi; " +
            "done";

        String output = shell.execute(script + " 2>/dev/null");
        if (output == null || output.isEmpty()) {
            // Fallback to root
            output = shell.execute("su -c '" + script + "'");
            if (output != null && !output.isEmpty()) {
                result.usedRoot = true;
            }
        }

        if (output == null || output.isEmpty()) {
            // Check if the directory exists at all
            String check = shell.execute("[ -d " + MODULES_DIR + " ] && echo exists || echo missing");
            if (!"exists".equals(check)) {
                check = shell.execute("su -c '[ -d " + MODULES_DIR + " ] && echo exists || echo missing'");
            }
            if ("missing".equals(check)) {
                result.error = new ScanError("scan", "Modules directory not found (no Magisk/KernelSU?)");
            } else {
                result.error = new ScanError("scan", "Cannot read " + MODULES_DIR + " (need root?)");
            }
            return result;
        }

        parseBatchedOutput(output, result);
        return result;
    }

    private void parseBatchedOutput(String output, ScanResult result) {
        String[] lines = output.split("\n");
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            if (line.startsWith("===MODULE:") && line.endsWith("===")) {
                String id = line.substring("===MODULE:".length(), line.length() - 3);
                StringBuilder prop = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].startsWith("===STATE:")) {
                    prop.append(lines[i]).append("\n");
                    i++;
                }
                ModuleInfo info = ModuleInfo.fromProp(id, prop.toString().trim());
                info.path = MODULES_DIR + "/" + id;
                info.iconPath = info.path + "/icon.png";

                // Parse state line
                if (i < lines.length && lines[i].startsWith("===STATE:")) {
                    String state = lines[i].substring("===STATE:".length());
                    // state line ends with ===, but the actual state values are after it
                    i++;
                    if (i < lines.length) {
                        String stateLine = lines[i].trim();
                        info.disabled = stateLine.contains("disable=1");
                        info.removePending = stateLine.contains("remove=1");
                        info.updatePending = stateLine.contains("update=1");
                        if (stateLine.contains("icon=0")) {
                            info.iconPath = null;
                        }
                    }
                    i++;
                }

                // Check for update directory with newer version
                if (info.updatePending) {
                    checkUpdateVersion(info);
                }

                result.modules.add(info);
            } else {
                i++;
            }
        }
    }

    /**
     * If the module has an update directory, read its module.prop to compare versions.
     */
    private void checkUpdateVersion(ModuleInfo info) {
        String updateDir = info.path + "/update";
        String prop = shell.execute("cat " + updateDir + "/module.prop 2>/dev/null");
        if (prop == null || prop.isEmpty()) {
            prop = shell.execute("su -c 'cat " + updateDir + "/module.prop'");
        }
        if (prop != null && !prop.isEmpty()) {
            // Parse new version code
            for (String line : prop.split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                if ("versionCode".equals(key)) {
                    try {
                        long newCode = Long.parseLong(val);
                        long curCode = 0;
                        if (info.versionCode != null && !info.versionCode.isEmpty()) {
                            curCode = Long.parseLong(info.versionCode);
                        }
                        info.updatePending = newCode > curCode;
                    } catch (NumberFormatException ignored) {
                    }
                    break;
                }
            }
        }
    }

    // ---- Xposed APK scan ----

    /**
     * Scan installed APKs for Xposed modules, like LSPatch's LSPPackageManager.
     * Detects both modern (META-INF/xposed/java_init.list) and legacy
     * (assets/xposed_init or xposedminversion meta-data) modules.
     */
    ScanResult scanXposedApks(PackageManager pm) {
        ScanResult result = new ScanResult();
        if (pm == null) {
            result.error = new ScanError("xposed_scan", "PackageManager unavailable");
            return result;
        }

        List<PackageInfo> packages;
        try {
            packages = pm.getInstalledPackages(
                    PackageManager.GET_META_DATA | PackageManager.GET_PERMISSIONS);
        } catch (Throwable t) {
            result.error = new ScanError("xposed_scan", "Cannot list packages: " + t.getMessage());
            return result;
        }

        for (PackageInfo pkg : packages) {
            if (pkg.applicationInfo == null) continue;
            ApplicationInfo app = pkg.applicationInfo;

            // Skip system apps that aren't updated
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                continue;
            }

            String sourceDir = app.sourceDir;
            if (sourceDir == null) continue;

            boolean isModern = false;
            boolean isLegacy = false;

            // Check meta-data for legacy xposedminversion
            if (app.metaData != null && app.metaData.containsKey("xposedminversion")) {
                isLegacy = true;
            }

            // Check APK contents for modern/legacy init files
            if (!isLegacy) {
                try (ZipFile zip = new ZipFile(sourceDir)) {
                    if (zip.getEntry("META-INF/xposed/java_init.list") != null) {
                        isModern = true;
                    } else if (zip.getEntry("assets/xposed_init") != null) {
                        isLegacy = true;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Cannot read APK: " + sourceDir, t);
                }
            }

            if (!isModern && !isLegacy) continue;

            // Determine Xposed API version
            int xposedApi = 0;
            if (app.metaData != null) {
                Object minVer = app.metaData.get("xposedminversion");
                if (minVer instanceof Integer) {
                    xposedApi = (Integer) minVer;
                } else if (minVer != null) {
                    try { xposedApi = Integer.parseInt(minVer.toString()); } catch (NumberFormatException ignored) {}
                }
            }

            // Try to read META-INF/xposed/module.prop for modern modules
            String description = "";
            if (isModern) {
                try (ZipFile zip = new ZipFile(sourceDir)) {
                    java.util.zip.ZipEntry propEntry = zip.getEntry("META-INF/xposed/module.prop");
                    if (propEntry != null) {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(zip.getInputStream(propEntry)))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                int eq = line.indexOf('=');
                                if (eq <= 0) continue;
                                String key = line.substring(0, eq).trim();
                                String val = line.substring(eq + 1).trim();
                                if ("description".equals(key)) description = val;
                                if ("targetApiVersion".equals(key)) {
                                    try { xposedApi = Integer.parseInt(val.trim()); } catch (NumberFormatException ignored) {}
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Cannot read module.prop from " + sourceDir, t);
                }
            }

            // Read scope from assets/xposed_scope or META-INF/xposed/scope
            List<String> scope = new ArrayList<>();
            try (ZipFile zip = new ZipFile(sourceDir)) {
                java.util.zip.ZipEntry scopeEntry = zip.getEntry("assets/xposed_scope");
                if (scopeEntry == null) scopeEntry = zip.getEntry("META-INF/xposed/scope");
                if (scopeEntry != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(zip.getInputStream(scopeEntry)))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            if (!line.isEmpty() && !line.startsWith("#")) scope.add(line);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Cannot read scope from " + sourceDir, t);
            }

            // Find settings activity from manifest category
            String settingsActivity = null;
            try {
                // Check for xposed module settings category in activities
                // (declared via android:name="de.robv.android.xposed.category.MODULE_SETTINGS")
                // We can't easily parse this without the hidden API, so leave null for now.
                // LSPatch uses SETTINGS_CATEGORY constant for this.
            } catch (Throwable ignored) {
            }

            String label = app.loadLabel(pm).toString();
            ModuleInfo info = ModuleInfo.fromXposedApk(
                    pkg.packageName, label,
                    pkg.versionName,
                    pkg.versionCode != 0 ? (long) pkg.versionCode : 0L,
                    description, sourceDir,
                    xposedApi > 0 ? xposedApi : (isModern ? 102 : 93),
                    settingsActivity);
            info.xposedScope.addAll(scope);

            result.modules.add(info);
        }

        return result;
    }
}
