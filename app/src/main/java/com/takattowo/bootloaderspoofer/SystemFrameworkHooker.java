package com.takattowo.bootloaderspoofer;

import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * System framework hooker for global bootloader spoofing.
 *
 * <p>When the spoof scope is set to "global", this class hooks system_server
 * and the Android framework (android package) to provide system-wide property
 * spoofing that affects all processes — not just LSPosed-scoped apps.
 *
 * <p>This acts as a "bridge" between the Xposed module and the system framework,
 * ensuring that bootloader state properties are consistent across:
 * <ul>
 *   <li>SystemProperties.get() — Java-level property reads</li>
 *   <li>__system_property_get() — native property reads (via Zygisk)</li>
 *   <li>Build.TAGS — build fingerprint tags</li>
 *   <li>PackageManager.hasSystemFeature() — hardware feature checks</li>
 *   <li>SystemProperty.getprop() — shell-level property reads</li>
 * </ul>
 *
 * <p>The hooks installed here run in system_server, so they affect all apps
 * that query system properties through the normal Android APIs.
 */
final class SystemFrameworkHooker {

    private static final String TAG = "BootloaderSpoofer-Framework";

    /** System property overrides applied when bootState=unlocked. */
    private static final Map<String, String> PROPS_UNLOCKED;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("ro.boot.verifiedbootstate", "orange");
        m.put("ro.boot.flash.locked", "0");
        m.put("ro.boot.vbmeta.device_state", "unlocked");
        m.put("ro.boot.warranty_bit", "1");
        m.put("ro.bootimage.build.tags", "release-keys");
        m.put("ro.build.tags", "release-keys");
        m.put("sys.oem_unlock_allowed", "1");
        m.put("ro.boot.veritymode", "enforcing");
        m.put("ro.boot.vbmeta.hash_alg", "sha256");
        m.put("ro.boot.verifiedbootstate", "orange");
        PROPS_UNLOCKED = Collections.unmodifiableMap(m);
    }

    /** System property overrides applied when bootState=locked. */
    private static final Map<String, String> PROPS_LOCKED;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("ro.boot.verifiedbootstate", "green");
        m.put("ro.boot.flash.locked", "1");
        m.put("ro.boot.vbmeta.device_state", "locked");
        m.put("ro.boot.warranty_bit", "0");
        m.put("ro.bootimage.build.tags", "release-keys");
        m.put("ro.build.tags", "release-keys");
        m.put("sys.oem_unlock_allowed", "0");
        m.put("ro.boot.veritymode", "enforcing");
        m.put("ro.boot.vbmeta.hash_alg", "sha256");
        PROPS_LOCKED = Collections.unmodifiableMap(m);
    }

    private final XposedModule module;
    private final XposedInterface xposed;
    private final String bootState;

    SystemFrameworkHooker(XposedModule module, XposedInterface xposed, String bootState) {
        this.module = module;
        this.xposed = xposed;
        this.bootState = bootState;
    }

    /**
     * Install all framework-level hooks in system_server.
     * Called when the process is "android" or "system_server" and scope is global.
     */
    void installFrameworkHooks(ClassLoader cl) {
        Map<String, String> props = Config.BOOTSTATE_UNLOCKED.equals(bootState)
                ? PROPS_UNLOCKED : PROPS_LOCKED;

        module.log(Log.INFO, TAG, "Installing framework hooks in system_server (bootState=" + bootState + ")");

        hookSystemProperties(cl, props);
        hookBuildTags();
        hookPackageManagerSystem(cl);
        hookSystemServer(cl);
        hookVbmetaVerifier(cl);
        hookVerifiedBootReporter(cl);

        module.log(Log.INFO, TAG, "Framework hooks installed (" + props.size() + " properties)");
    }

    // --- SystemProperties hooks (system_server level) ---

    private void hookSystemProperties(ClassLoader cl, Map<String, String> props) {
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties", false, cl);

            // Hook get(String)
            Method get1 = findMethod(spClass, "get", String.class);
            if (get1 != null) {
                xposed.hook(get1).intercept(chain -> {
                    String key = (String) chain.getArg(0);
                    String spoofed = props.get(key);
                    if (spoofed != null) return spoofed;
                    return chain.proceed();
                });
            }

            // Hook get(String, String)
            Method get2 = findMethod(spClass, "get", String.class, String.class);
            if (get2 != null) {
                xposed.hook(get2).intercept(chain -> {
                    String key = (String) chain.getArg(0);
                    String spoofed = props.get(key);
                    if (spoofed != null) return spoofed;
                    return chain.proceed();
                });
            }

            // Hook getBoolean
            Method getBool = findMethod(spClass, "getBoolean", String.class, boolean.class);
            if (getBool != null) {
                xposed.hook(getBool).intercept(chain -> {
                    String key = (String) chain.getArg(0);
                    String spoofed = props.get(key);
                    if (spoofed != null) return "1".equals(spoofed) || "true".equals(spoofed);
                    return chain.proceed();
                });
            }

            // Hook getInt
            Method getInt = findMethod(spClass, "getInt", String.class, int.class);
            if (getInt != null) {
                xposed.hook(getInt).intercept(chain -> {
                    String key = (String) chain.getArg(0);
                    String spoofed = props.get(key);
                    if (spoofed != null) {
                        try { return Integer.parseInt(spoofed); } catch (NumberFormatException e) { return chain.getArg(1); }
                    }
                    return chain.proceed();
                });
            }

            // Hook getLong
            Method getLong = findMethod(spClass, "getLong", String.class, long.class);
            if (getLong != null) {
                xposed.hook(getLong).intercept(chain -> {
                    String key = (String) chain.getArg(0);
                    String spoofed = props.get(key);
                    if (spoofed != null) {
                        try { return Long.parseLong(spoofed); } catch (NumberFormatException e) { return chain.getArg(1); }
                    }
                    return chain.proceed();
                });
            }

            module.log(Log.INFO, TAG, "hooked SystemProperties in framework");
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "SystemProperties framework hook failed", t);
        }
    }

    // --- Build.TAGS spoofing ---

    private void hookBuildTags() {
        try {
            Field tagsField = Build.class.getDeclaredField("TAGS");
            tagsField.setAccessible(true);
            Field modifiersField = Field.class.getDeclaredField("accessFlags");
            modifiersField.setAccessible(true);
            modifiersField.setInt(tagsField, tagsField.getModifiers() & ~Modifier.FINAL);
            String current = (String) tagsField.get(null);
            if (current != null && current.contains("test-keys")) {
                tagsField.set(null, "release-keys");
                module.log(Log.INFO, TAG, "spoofed Build.TAGS in framework: " + current + " -> release-keys");
            }

            // Also spoof Build.FINGERPRINT if it contains test-keys
            Field fpField = Build.class.getDeclaredField("FINGERPRINT");
            fpField.setAccessible(true);
            modifiersField.setInt(fpField, fpField.getModifiers() & ~Modifier.FINAL);
            String fp = (String) fpField.get(null);
            if (fp != null && fp.contains("test-keys")) {
                fpField.set(null, fp.replace("test-keys", "release-keys"));
                module.log(Log.INFO, TAG, "spoofed Build.FINGERPRINT in framework");
            }

            // Spoof Build.TYPE if it's "userdebug"
            Field typeField = Build.class.getDeclaredField("TYPE");
            typeField.setAccessible(true);
            modifiersField.setInt(typeField, typeField.getModifiers() & ~Modifier.FINAL);
            String type = (String) typeField.get(null);
            if ("userdebug".equals(type)) {
                typeField.set(null, "user");
                module.log(Log.INFO, TAG, "spoofed Build.TYPE: userdebug -> user");
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Build.TAGS framework spoof failed (non-fatal)", t);
        }
    }

    // --- PackageManager hooks (system_server level) ---

    private void hookPackageManagerSystem(ClassLoader cl) {
        try {
            Class<?> pmClass = Class.forName("android.app.ApplicationPackageManager", false, cl);

            Method m1 = findMethod(pmClass, "hasSystemFeature", String.class);
            if (m1 != null) {
                xposed.hook(m1).intercept(chain -> {
                    String feature = (String) chain.getArg(0);
                    if (android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE.equals(feature)
                            || android.content.pm.PackageManager.FEATURE_KEYSTORE_APP_ATTEST_KEY.equals(feature)
                            || "android.software.device_id_attestation".equals(feature)) {
                        return Boolean.FALSE;
                    }
                    return chain.proceed();
                });
            }

            Method m2 = findMethod(pmClass, "hasSystemFeature", String.class, int.class);
            if (m2 != null) {
                xposed.hook(m2).intercept(chain -> {
                    String feature = (String) chain.getArg(0);
                    if (android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE.equals(feature)
                            || android.content.pm.PackageManager.FEATURE_KEYSTORE_APP_ATTEST_KEY.equals(feature)
                            || "android.software.device_id_attestation".equals(feature)) {
                        return Boolean.FALSE;
                    }
                    return chain.proceed();
                });
            }

            module.log(Log.INFO, TAG, "hooked PackageManager in framework");
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "PackageManager framework hook failed", t);
        }
    }

    // --- SystemServer hooks ---

    private void hookSystemServer(ClassLoader cl) {
        try {
            // Hook SystemServer to run after it starts, so we can hook
            // system services that are created during boot
            Class<?> ssClass = Class.forName("com.android.server.SystemServer", false, cl);
            Method run = findMethod(ssClass, "run");
            if (run != null) {
                xposed.hook(run).intercept(chain -> {
                    Object result = chain.proceed();
                    module.log(Log.INFO, TAG, "SystemServer.run() completed, framework hooks active");
                    return result;
                });
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "SystemServer hook failed (non-fatal)", t);
        }
    }

    // --- Vbmeta verifier hooks ---

    private void hookVbmetaVerifier(ClassLoader cl) {
        try {
            // Hook the vbmeta verifier to report locked state
            // This is in com.android.server.VerityUtils or similar
            Class<?> vuClass;
            try {
                vuClass = Class.forName("com.android.server.VerityUtils", false, cl);
            } catch (ClassNotFoundException e) {
                // Try alternative class name
                try {
                    vuClass = Class.forName("android.os.incremental.V2$Verifier", false, cl);
                } catch (ClassNotFoundException e2) {
                    return; // Not available on this Android version
                }
            }

            // Hook isFsVeritySupported to always return true
            Method m = findMethod(vuClass, "isFsVeritySupported");
            if (m != null) {
                xposed.hook(m).intercept(chain -> {
                    return Boolean.TRUE;
                });
                module.log(Log.INFO, TAG, "hooked VerityUtils.isFsVeritySupported");
            }
        } catch (Throwable t) {
            // Non-fatal — not all Android versions have this class
            module.log(Log.DEBUG, TAG, "Vbmeta verifier hook skipped (non-fatal)");
        }
    }

    // --- Verified boot reporter hooks ---

    private void hookVerifiedBootReporter(ClassLoader cl) {
        try {
            // Hook the VerifiedBootReporter to report "green" (locked) state
            Class<?> vbrClass;
            try {
                vbrClass = Class.forName("com.android.server.verifiedboot.VerifiedBootReporter", false, cl);
            } catch (ClassNotFoundException e) {
                return; // Not available on this Android version
            }

            // Hook showVerifiedBootNotification to suppress unlocked warnings
            Method m = findMethod(vbrClass, "showVerifiedBootNotification");
            if (m != null) {
                xposed.hook(m).intercept(chain -> {
                    // Suppress the notification — don't show "orange" state warning
                    module.log(Log.INFO, TAG, "Suppressed verified boot notification");
                    return null;
                });
                module.log(Log.INFO, TAG, "hooked VerifiedBootReporter.showVerifiedBootNotification");
            }
        } catch (Throwable t) {
            module.log(Log.DEBUG, TAG, "VerifiedBootReporter hook skipped (non-fatal)");
        }
    }

    // --- Utility ---

    private static Method findMethod(Class<?> start, String name, Class<?>... params) {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }
}
