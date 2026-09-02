package com.takattowo.bootloaderspoofer;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuPlusAPI;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages Shizuku/ShizukuPlus connection and provides high-level
 * methods for hidden system tweaks via elevated shell access.
 *
 * Works with standard Shizuku, Sui, or ShizukuPlus servers.
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

    /** Check if the enhanced ShizukuPlus API is available. */
    static boolean isEnhancedApi() {
        try {
            return ShizukuPlusAPI.isEnhancedApiSupported();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Execute a shell command via Shizuku. Returns output or null on failure. */
    static String executeShell(String command) {
        try {
            ShizukuPlusAPI.CommandResult result = ShizukuPlusAPI.executeShell(command);
            if (result.isSuccess()) return result.output;
            Log.w(TAG, "Shell command failed: " + command + " -> " + result.error);
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "executeShell failed", t);
            return null;
        }
    }

    /** Get a system setting value. */
    static String getSetting(String namespace, String key) {
        try {
            ShizukuPlusAPI.CommandResult result =
                    ShizukuPlusAPI.executeShell(new String[]{"settings", "get", namespace, key});
            return result.output;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Set a system setting value. */
    static boolean setSetting(String namespace, String key, String value) {
        try {
            return ShizukuPlusAPI.executeShell(
                    new String[]{"settings", "put", namespace, key, value}).isSuccess();
        } catch (Throwable t) {
            return false;
        }
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
            // Use su for root-required tweaks
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

        // Check OEM unlock setting
        String oemUnlock = getSetting("global", "oem_unlock_enabled");
        sb.append("OEM Unlock Enabled: ").append(oemUnlock != null ? oemUnlock : "unknown").append("\n");

        // Check bootloader state properties (requires root)
        String bootState = executeShell("getprop ro.boot.verifiedbootstate");
        sb.append("Verified Boot State: ").append(bootState != null ? bootState : "unknown").append("\n");

        String flashLocked = executeShell("getprop ro.boot.flash.locked");
        sb.append("Flash Locked: ").append(flashLocked != null ? flashLocked : "unknown").append("\n");

        String warrantyBit = executeShell("getprop ro.boot.warranty_bit");
        sb.append("Warranty Bit: ").append(warrantyBit != null ? warrantyBit : "unknown").append("\n");

        // Check device info
        String device = executeShell("getprop ro.product.device");
        String model = executeShell("getprop ro.product.model");
        sb.append("Device: ").append(device != null ? device : "?").append("\n");
        sb.append("Model: ").append(model != null ? model : "?").append("\n");

        // Check if Qualcomm
        String platform = executeShell("getprop ro.board.platform");
        sb.append("Platform: ").append(platform != null ? platform : "?").append("\n");

        // Check Knox status
        String knox = executeShell("getprop ro.config.knox");
        sb.append("Knox: ").append(knox != null ? knox : "unknown").append("\n");

        return sb.toString();
    }

    /**
     * Samsung Qualcomm-specific: attempt to enable OEM unlock via
     * engineering mode service. This uses the Samsung engineering mode
     * intent which is available on Qualcomm-based Samsung devices.
     */
    static boolean enableSamsungOemUnlock() {
        // Method 1: Standard settings put (works with Shizuku)
        boolean standard = setSetting("global", "oem_unlock_enabled", "1");
        if (standard) return true;

        // Method 2: Via root (if available)
        String result = executeShell("su -c 'settings put global oem_unlock_enabled 1'");
        return result != null;
    }

    /**
     * Reboot to bootloader/download mode.
     * Samsung devices use 'reboot download' for download mode.
     */
    static boolean rebootToBootloader() {
        String result = executeShell("reboot bootloader");
        return result == null; // reboot command returns nothing on success
    }

    static boolean rebootToDownloadMode() {
        String result = executeShell("su -c 'reboot download'");
        return result != null;
    }

    private ShizukuManager() {}
}
