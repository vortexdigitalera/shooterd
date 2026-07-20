package com.takattowo.bootloaderspoofer;

import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;
import java.security.SecureRandom;

final class BootKey {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile byte[] cachedBootKey;
    private static volatile byte[] cachedBootHash;

    static byte[] getBootKey() {
        byte[] k = cachedBootKey;
        if (k == null) {
            synchronized (BootKey.class) {
                k = cachedBootKey;
                if (k == null) {
                    k = randomBytes(32);
                    cachedBootKey = k;
                }
            }
        }
        return k;
    }

    static byte[] getBootHash() {
        byte[] h = cachedBootHash;
        if (h == null) {
            synchronized (BootKey.class) {
                h = cachedBootHash;
                if (h == null) {
                    h = bootHashFromProp();
                    if (h == null) h = randomBytes(32);
                    cachedBootHash = h;
                }
            }
        }
        return h;
    }

    private static byte[] bootHashFromProp() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getDeclaredMethod("get", String.class, String.class);
            String b = (String) get.invoke(null, "ro.boot.vbmeta.digest", null);
            if (b == null || b.length() != 64) return null;
            return hexToBytes(b);
        } catch (Throwable t) {
            Log.w(ModuleMain.TAG, "bootHashFromProp failed: " + t);
            return null;
        }
    }

    private static byte[] hexToBytes(String s) {
        int n = s.length();
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
            out[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return out;
    }

    private static byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        RANDOM.nextBytes(b);
        return b;
    }

    static int getOsVersion() {
        return getOsVersion(Build.VERSION.SDK_INT);
    }

    static int getOsVersion(int sdk) {
        return switch (sdk) {
            case 26 -> 80000;
            case 27 -> 81000;
            case 28 -> 90000;
            case 29 -> 100000;
            case 30 -> 110000;
            case 31, 32 -> 120000;
            case 33 -> 130000;
            case 34 -> 140000;
            case 35 -> 150000;
            case 36 -> 160000;
            case 37 -> 170000;
            default -> sdk > 37 ? (sdk - 20) * 10000 : 80000;
        };
    }

    static int getAttestationVersion() {
        return getAttestationVersion(Build.VERSION.SDK_INT);
    }

    static int getAttestationVersion(int sdk) {
        if (sdk <= 27) return 2;
        if (sdk == 28) return 3;
        if (sdk <= 30) return 4;
        if (sdk <= 32) return 100;
        if (sdk == 33) return 200;
        if (sdk <= 35) return 300;
        if (sdk == 36) return 400;
        return 500;
    }

    static int getKeymasterVersion() {
        return getKeymasterVersion(Build.VERSION.SDK_INT);
    }

    static int getKeymasterVersion(int sdk) {
        if (sdk <= 27) return 3;
        if (sdk == 28) return 4;
        if (sdk <= 30) return 41;
        return getAttestationVersion(sdk);
    }

    static int getPatchLevel() {
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, false);
    }

    static Long getVendorPatchLevel() {
        return patchLevelFromProp("ro.vendor.build.security_patch");
    }

    static Long getBootPatchLevel() {
        return patchLevelFromProp("ro.bootimage.build.security_patch");
    }

    private static Long patchLevelFromProp(String property) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getDeclaredMethod("get", String.class, String.class);
            String patch = (String) get.invoke(null, property, "");
            if (patch == null || patch.isEmpty()) return null;
            return (long) convertPatchLevel(patch, true);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int convertPatchLevel(String patch, boolean longForm) {
        try {
            String[] parts = patch.split("-");
            int y = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            int d = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return longForm ? y * 10000 + m * 100 + d : y * 100 + m;
        } catch (Throwable t) {
            return 202404;
        }
    }

    private BootKey() {}
}
