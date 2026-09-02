package com.takattowo.bootloaderspoofer;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import rikka.shizuku.Shizuku;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages Shizuku connection and provides high-level methods for
 * hidden system tweaks via elevated shell access.
 *
 * Uses the stock Shizuku-API from RikkaApps/Shizuku-API.
 * All shell commands run as uid 2000 (shell) — no root required.
 */
final class ShizukuManager {

    private static final String TAG = "BootloaderSpoofer-Shizuku";
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    private static volatile boolean initialized = false;

    /** A system tweak that can be toggled via Shizuku shell. */
    static final class Tweak {
        final String key;
        final String title;
        final String description;
        final String namespace;     // "global", "secure", "system"
        final String settingKey;
        final String onValue;
        final String offValue;
        final boolean requiresRoot;

        Tweak(String key, String title, String description,
              String namespace, String settingKey,
              String onValue, String offValue, boolean requiresRoot) {
            this.key = key;
            this.title = title;
            this.description = description;
            this.namespace = namespace;
            this.settingKey = settingKey;
            this.onValue = onValue;
            this.offValue = offValue;
            this.requiresRoot = requiresRoot;
        }
    }

    /** All available system tweaks. */
    static final Map<String, Tweak> TWEAKS = new LinkedHashMap<>();
    static {
        // --- Bootloader / OEM Unlock ---
        TWEAKS.put("oem_unlock", new Tweak(
                "oem_unlock", "OEM Unlocking",
                "Allow bootloader unlocking via fastboot",
                "global", "oem_unlock_enabled", "1", "0", false
        ));
        TWEAKS.put("oem_unlock_samsung", new Tweak(
                "oem_unlock_samsung", "Samsung OEM Unlock (Qualcomm)",
                "Enable OEM unlock via Samsung engineering mode (Qualcomm devices)",
                "global", "oem_unlock_enabled", "1", "0", true
        ));

        // --- Developer Options ---
        TWEAKS.put("adb_enabled", new Tweak(
                "adb_enabled", "ADB over USB",
                "Enable Android Debug Bridge over USB",
                "global", "adb_enabled", "1", "0", false
        ));
        TWEAKS.put("adb_wifi_enabled", new Tweak(
                "adb_wifi_enabled", "ADB over WiFi",
                "Enable wireless debugging (Android 11+)",
                "global", "adb_wifi_enabled", "1", "0", false
        ));
        TWEAKS.put("development_settings_enabled", new Tweak(
                "development_settings_enabled", "Developer Options",
                "Show Developer Options in Settings",
                "global", "development_settings_enabled", "1", "0", false
        ));
        TWEAKS.put("stay_on_while_plugged_in", new Tweak(
                "stay_on_while_plugged_in", "Stay Awake Charging",
                "Keep screen on while plugged in",
                "global", "stay_on_while_plugged_in", "7", "0", false
        ));

        // --- Display ---
        TWEAKS.put("screen_brightness_mode", new Tweak(
                "screen_brightness_mode", "Adaptive Brightness",
                "Toggle adaptive/auto brightness",
                "system", "screen_brightness_mode", "1", "0", false
        ));
        TWEAKS.put("screen_off_timeout", new Tweak(
                "screen_off_timeout", "Screen Timeout (10 min)",
                "Set screen off timeout to 10 minutes",
                "system", "screen_off_timeout", "600000", "30000", false
        ));
        TWEAKS.put("window_animation_scale", new Tweak(
                "window_animation_scale", "Window Animations",
                "Enable/disable window animations",
                "global", "window_animation_scale", "1.0", "0.0", false
        ));
        TWEAKS.put("transition_animation_scale", new Tweak(
                "transition_animation_scale", "Transition Animations",
                "Enable/disable transition animations",
                "global", "transition_animation_scale", "1.0", "0.0", false
        ));
        TWEAKS.put("animator_duration_scale", new Tweak(
                "animator_duration_scale", "Animator Duration",
                "Enable/disable animator duration scale",
                "global", "animator_duration_scale", "1.0", "0.0", false
        ));

        // --- Samsung-specific (Qualcomm) ---
        TWEAKS.put("samsung_knox_warranty", new Tweak(
                "samsung_knox_warranty", "Samsung Knox Warranty Bit",
                "Check/set Knox warranty bit (Qualcomm Samsung devices)",
                "global", "oem_unlock_enabled", "1", "0", true
        ));
        TWEAKS.put("samsung_eng_mode", new Tweak(
                "samsung_eng_mode", "Samsung Engineering Mode",
                "Enable Samsung engineering mode for Qualcomm devices",
                "global", "development_settings_enabled", "1", "0", true
        ));
        TWEAKS.put("samsung_usb_config", new Tweak(
                "samsung_usb_config", "Samsung USB Config (ADB+MTP)",
                "Set USB configuration to ADB+MTP on Samsung devices",
                "global", "adb_enabled", "1", "0", true
        ));

        // --- Bootloader state properties (via setprop, requires root) ---
        TWEAKS.put("verified_boot_state", new Tweak(
                "verified_boot_state", "Verified Boot State",
                "Set ro.boot.verifiedbootstate property (requires root)",
                "global", "verified_boot_state", "green", "orange", true
        ));
        TWEAKS.put("flash_locked", new Tweak(
                "flash_locked", "Flash Lock State",
                "Set ro.boot.flash.locked property (requires root)",
                "global", "flash_locked", "1", "0", true
        ));
    }

    /** Initialize Shizuku connection listeners. Call from Application.onCreate. */
    static void init() {
        if (initialized) return;
        initialized = true;
        try {
            // Initialize Sui if available (for root-based Shizuku)
            try {
                Class<?> suiClass = Class.forName("rikka.sui.Sui");
                Method initMethod = suiClass.getMethod("init");
                initMethod.invoke(null);
            } catch (Throwable t) {
                // Sui not available, that's fine
            }

            Shizuku.addBinderReceivedListenerSticky(() ->
                    Log.i(TAG, "Shizuku binder received"));
            Shizuku.addBinderDeadListener(() ->
                    Log.w(TAG, "Shizuku binder died"));
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku init failed (not installed?)", t);
        }
    }

    /** Check if Shizuku is installed. */
    static boolean isInstalled(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0) != null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Check if Shizuku is running and has permission. */
    static boolean isConnected() {
        try {
            return Shizuku.pingBinder() && checkPermission();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Check if we have Shizuku permission. */
    static boolean checkPermission() {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true;
        } catch (Throwable t) {
            Log.w(TAG, "checkPermission failed", t);
        }
        return false;
    }

    /** Request Shizuku permission. */
    static void requestPermission() {
        try {
            Shizuku.requestPermission(0);
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission failed", t);
        }
    }

    /**
     * Execute a shell command via Shizuku using the stock API.
     * Uses reflection to access Shizuku.newProcess() which is package-private.
     * Returns output or null on failure.
     */
    static String executeShell(String command) {
        return executeShell(new String[]{"sh", "-c", command});
    }

    /**
     * Execute a shell command with arguments via Shizuku.
     */
    static String executeShell(String[] cmd) {
        try {
            Process process = newProcess(cmd);
            if (process == null) return null;

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            process.waitFor();
            return output.toString().trim();
        } catch (Throwable t) {
            Log.w(TAG, "executeShell failed", t);
            return null;
        }
    }

    /**
     * Call Shizuku.newProcess() via reflection (it's package-private in the stock API).
     */
    private static Process newProcess(String[] cmd) {
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess",
                    String[].class, String[].class, String.class);
            m.setAccessible(true);
            return (Process) m.invoke(null, cmd, null, null);
        } catch (Throwable t) {
            Log.w(TAG, "newProcess reflection failed", t);
            return null;
        }
    }

    /** Get a system setting value. */
    static String getSetting(String namespace, String key) {
        return executeShell(new String[]{"settings", "get", namespace, key});
    }

    /** Set a system setting value. */
    static boolean setSetting(String namespace, String key, String value) {
        String result = executeShell(new String[]{"settings", "put", namespace, key, value});
        return result != null; // no error output = success
    }

    /** Get the current state of a tweak (on/off). */
    static boolean isTweakEnabled(Tweak tweak) {
        String value = getSetting(tweak.namespace, tweak.settingKey);
        if (value == null) return false;
        return value.trim().equals(tweak.onValue);
    }

    /** Apply a tweak (set to on value). */
    static boolean enableTweak(Tweak tweak) {
        if (tweak.requiresRoot) {
            String result = executeShell("su -c 'settings put " + tweak.namespace
                    + " " + tweak.settingKey + " " + tweak.onValue + "'");
            return result != null;
        }
        return setSetting(tweak.namespace, tweak.settingKey, tweak.onValue);
    }

    /** Disable a tweak (set to off value). */
    static boolean disableTweak(Tweak tweak) {
        if (tweak.requiresRoot) {
            String result = executeShell("su -c 'settings put " + tweak.namespace
                    + " " + tweak.settingKey + " " + tweak.offValue + "'");
            return result != null;
        }
        return setSetting(tweak.namespace, tweak.settingKey, tweak.offValue);
    }

    /** Toggle a tweak. Returns new state. */
    static boolean toggleTweak(Tweak tweak) {
        if (isTweakEnabled(tweak)) {
            disableTweak(tweak);
            return false;
        } else {
            enableTweak(tweak);
            return true;
        }
    }

    /**
     * Samsung-specific: check bootloader unlock capability via engineering mode.
     * Works on Qualcomm-based Samsung devices (S10e, S10, Note10, etc.)
     */
    static String checkSamsungBootloaderStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Samsung Bootloader Status ===\n");

        String oemUnlock = getSetting("global", "oem_unlock_enabled");
        sb.append("OEM Unlock Enabled: ").append(oemUnlock != null ? oemUnlock : "unknown").append("\n");

        String bootState = executeShell("getprop ro.boot.verifiedbootstate");
        sb.append("Verified Boot State: ").append(bootState != null ? bootState : "unknown").append("\n");

        String flashLocked = executeShell("getprop ro.boot.flash.locked");
        sb.append("Flash Locked: ").append(flashLocked != null ? flashLocked : "unknown").append("\n");

        String warrantyBit = executeShell("getprop ro.boot.warranty_bit");
        sb.append("Warranty Bit: ").append(warrantyBit != null ? warrantyBit : "unknown").append("\n");

        String device = executeShell("getprop ro.product.device");
        String model = executeShell("getprop ro.product.model");
        sb.append("Device: ").append(device != null ? device : "?").append("\n");
        sb.append("Model: ").append(model != null ? model : "?").append("\n");

        String platform = executeShell("getprop ro.board.platform");
        sb.append("Platform: ").append(platform != null ? platform : "?").append("\n");

        String knox = executeShell("getprop ro.config.knox");
        sb.append("Knox: ").append(knox != null ? knox : "unknown").append("\n");

        return sb.toString();
    }

    /**
     * Samsung Qualcomm-specific: attempt to enable OEM unlock.
     */
    static boolean enableSamsungOemUnlock() {
        boolean standard = setSetting("global", "oem_unlock_enabled", "1");
        if (standard) return true;

        String result = executeShell("su -c 'settings put global oem_unlock_enabled 1'");
        return result != null;
    }

    /**
     * Reboot to bootloader/download mode.
     */
    static boolean rebootToBootloader() {
        String result = executeShell("reboot bootloader");
        return result == null;
    }

    static boolean rebootToDownloadMode() {
        String result = executeShell("su -c 'reboot download'");
        return result != null;
    }

    private ShizukuManager() {}
}
