package com.takattowo.bootloaderspoofer;

/** Pure constants. No framework imports - safe to load in module process. */
final class Config {

    static final String KEYBOX_FILE = "keybox.xml";
    static final String MODE_FILE = "mode.txt";
    static final String BOOTSTATE_FILE = "bootstate.txt";
    static final String SPOOFSCOPE_FILE = "spoofscope.txt";
    static final String ZYGISK_FILE = "zygisk_mode.txt";

    static final String MODE_LEAF_HACK = "leaf_hack";
    static final String MODE_CERT_GENERATE = "cert_generate";
    static final String MODE_OFF = "off";

    static final String BOOTSTATE_LOCKED = "locked";
    static final String BOOTSTATE_UNLOCKED = "unlocked";

    /** Spoof scope: which processes get hooks. */
    static final String SCOPE_SCOPED = "scoped";   // Only LSPosed-scoped apps (default)
    static final String SCOPE_GLOBAL = "global";   // All processes including system_server

    /** Zygisk integration mode. */
    static final String ZYGISK_OFF = "off";         // No Zygisk, LSPosed only
    static final String ZYGISK_PASSIVE = "passive"; // Zygisk loads, LSPosed still hooks
    static final String ZYGISK_ACTIVE = "active";  // Zygisk does native-level spoofing

    static String normalizeMode(String raw) {
        if (raw == null) return MODE_LEAF_HACK;
        String m = raw.trim().toLowerCase();
        switch (m) {
            case MODE_LEAF_HACK:
            case MODE_CERT_GENERATE:
            case MODE_OFF:
                return m;
            default:
                return MODE_LEAF_HACK;
        }
    }

    static String normalizeBootState(String raw) {
        if (raw == null) return BOOTSTATE_LOCKED;
        return BOOTSTATE_UNLOCKED.equals(raw.trim().toLowerCase())
                ? BOOTSTATE_UNLOCKED : BOOTSTATE_LOCKED;
    }

    static String normalizeSpoofScope(String raw) {
        if (raw == null) return SCOPE_SCOPED;
        return SCOPE_GLOBAL.equals(raw.trim().toLowerCase())
                ? SCOPE_GLOBAL : SCOPE_SCOPED;
    }

    static String normalizeZygiskMode(String raw) {
        if (raw == null) return ZYGISK_OFF;
        String m = raw.trim().toLowerCase();
        switch (m) {
            case ZYGISK_PASSIVE:
            case ZYGISK_ACTIVE:
                return m;
            default:
                return ZYGISK_OFF;
        }
    }

    private Config() {}
}
