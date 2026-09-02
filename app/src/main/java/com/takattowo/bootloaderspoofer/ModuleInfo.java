package com.takattowo.bootloaderspoofer;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a module parsed from either a Magisk/KernelSU directory
 * ({@code /data/adb/modules/&lt;id&gt;/module.prop}) or an installed Xposed
 * module APK (detected via {@code META-INF/xposed/java_init.list} or
 * {@code assets/xposed_init}).
 *
 * <p>Standard module.prop fields: id, name, version, versionCode, author, description.
 *
 * <p>Magisk directory module state is determined by marker files:
 * <ul>
 *   <li>{@code disable} — module is disabled</li>
 *   <li>{@code remove}   — module is marked for removal on next boot</li>
 *   <li>{@code update}   — module has a pending update (new module.prop in update dir)</li>
 * </ul>
 *
 * <p>Xposed module APKs carry additional metadata: the Xposed API level
 * ({@code xposedminversion} meta-data or {@code targetApiVersion} in
 * {@code META-INF/xposed/module.prop}), the scope ({@code assets/xposed_scope}
 * or {@code META-INF/xposed/scope}), and the package name.
 */
final class ModuleInfo {

    /** Module source: Magisk/KernelSU directory or installed Xposed APK. */
    enum Source { MAGISK_DIR, XPOSED_APK }

    final String id;
    final String name;
    final String version;
    final String versionCode;
    final String author;
    final String description;
    final Source source;

    // --- Magisk directory state ---
    boolean disabled;
    boolean removePending;
    boolean updatePending;

    /** Path to the module directory (Magisk) or APK (Xposed). */
    String path;

    /** Path to module icon, if available (Magisk: dir/icon.png). */
    String iconPath;

    // --- Xposed APK fields ---
    /** Package name for Xposed APK modules. */
    String packageName;
    /** Xposed API version (e.g. 93, 100, 102). 0 if unknown. */
    int xposedApi;
    /** Scope list (package names) for Xposed modules. Empty if global. */
    final List<String> xposedScope = new ArrayList<>();
    /** Settings activity class name, if declared in manifest. */
    String settingsActivity;

    ModuleInfo(String id, String name, String version, String versionCode,
               String author, String description, Source source) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.versionCode = versionCode;
        this.author = author;
        this.description = description;
        this.source = source;
    }

    /** Parse a module.prop file content into a ModuleInfo (Magisk directory source). */
    static ModuleInfo fromProp(String id, String propContent) {
        String name = id;
        String version = "";
        String versionCode = "";
        String author = "";
        String description = "";

        if (propContent != null) {
            for (String line : propContent.split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                switch (key) {
                    case "name":        name = val; break;
                    case "version":      version = val; break;
                    case "versionCode":  versionCode = val; break;
                    case "author":       author = val; break;
                    case "description":  description = val; break;
                }
            }
        }
        return new ModuleInfo(id, name, version, versionCode, author, description, Source.MAGISK_DIR);
    }

    /** Create a ModuleInfo from an installed Xposed APK. */
    static ModuleInfo fromXposedApk(String packageName, String label, String versionName,
                                    long versionCode, String description, String apkPath,
                                    int xposedApi, String settingsActivity) {
        ModuleInfo info = new ModuleInfo(
                packageName, label, versionName, String.valueOf(versionCode),
                "", description, Source.XPOSED_APK);
        info.packageName = packageName;
        info.path = apkPath;
        info.xposedApi = xposedApi;
        info.settingsActivity = settingsActivity;
        return info;
    }

    @Override
    public String toString() {
        return "ModuleInfo{id=" + id + ", name=" + name + ", source=" + source + "}";
    }
}
