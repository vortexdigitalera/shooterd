package com.takattowo.bootloaderspoofer;

/**
 * Represents a Magisk/KernelSU module parsed from /data/adb/modules/&lt;id&gt;/module.prop.
 *
 * Standard module.prop fields:
 *   id, name, version, versionCode, author, description
 *
 * Module state is determined by marker files inside the module directory:
 *   - "disable" file present  → module is disabled
 *   - "remove"   file present → module is marked for removal on next boot
 *   - "update"   file present  → module has a pending update
 */
final class ModuleInfo {

    final String id;
    final String name;
    final String version;
    final String versionCode;
    final String author;
    final String description;
    boolean disabled;
    boolean removePending;
    boolean updatePending;

    ModuleInfo(String id, String name, String version, String versionCode,
               String author, String description) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.versionCode = versionCode;
        this.author = author;
        this.description = description;
    }

    /** Parse a module.prop file content into a ModuleInfo. */
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
        return new ModuleInfo(id, name, version, versionCode, author, description);
    }
}
