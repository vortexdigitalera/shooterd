package com.takattowo.bootloaderspoofer;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import rikka.shizuku.Shizuku;

/**
 * Debug info page showing system properties, kernel info, OEM status,
 * attestation tests, and root/Zygisk detection — for developer use.
 *
 * Similar to KernelSU manager's debug info or Magisk's status page.
 */
public class DebugInfoActivity extends AppCompatActivity {

    private LinearLayout container;
    private TextView shizukuStatus;

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(() -> {
                        toast(getString(R.string.shizuku_permission_granted));
                        refreshAll();
                    });
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug_info);

        ImageButton back = findViewById(R.id.btn_back);
        back.setOnClickListener(v -> finish());

        container = findViewById(R.id.debug_container);
        shizukuStatus = findViewById(R.id.shizuku_status);

        ShizukuManager.init();
        Shizuku.addRequestPermissionResultListener(permissionListener);
        ShizukuManager.addBinderStateListener(available -> runOnUiThread(this::refreshAll));

        refreshAll();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(permissionListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ShizukuManager.requestBinder(this);
        refreshAll();
    }

    private void refreshAll() {
        container.removeAllViews();

        updateShizukuStatus();
        addDeviceInfo();
        addBootloaderProps();
        addKernelInfo();
        addOemInfo();
        addRootDetection();
        addAttestationTest();
        addAllProps();
    }

    private void updateShizukuStatus() {
        if (!ShizukuManager.isInstalled(this)) {
            shizukuStatus.setText(R.string.shizuku_not_installed);
        } else if (ShizukuManager.isConnected()) {
            shizukuStatus.setText(R.string.shizuku_connected);
        } else if (ShizukuManager.isRunning()) {
            shizukuStatus.setText(R.string.shizuku_not_granted);
        } else {
            shizukuStatus.setText(R.string.shizuku_not_running);
        }
    }

    // --- Device info ---

    private void addDeviceInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("Manufacturer", Build.MANUFACTURER);
        info.put("Model", Build.MODEL);
        info.put("Device", Build.DEVICE);
        info.put("Product", Build.PRODUCT);
        info.put("Brand", Build.BRAND);
        info.put("Board", Build.BOARD);
        info.put("Hardware", Build.HARDWARE);
        info.put("Display", Build.DISPLAY);
        info.put("Build ID", Build.ID);
        info.put("Fingerprint", Build.FINGERPRINT);
        info.put("Tags", Build.TAGS);
        info.put("Type", Build.TYPE);
        info.put("SDK", String.valueOf(Build.VERSION.SDK_INT));
        info.put("Release", Build.VERSION.RELEASE);
        info.put("Security patch", Build.VERSION.SECURITY_PATCH);
        info.put("ABI", Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "unknown");

        addSection(R.string.debug_device_info, info);
    }

    // --- Bootloader properties ---

    private void addBootloaderProps() {
        Map<String, String> props = new LinkedHashMap<>();
        String[] keys = {
            "ro.boot.verifiedbootstate",
            "ro.boot.flash.locked",
            "ro.boot.vbmeta.device_state",
            "ro.boot.warranty_bit",
            "ro.boot.veritymode",
            "ro.boot.vbmeta.hash_alg",
            "ro.bootimage.build.tags",
            "ro.build.tags",
            "sys.oem_unlock_allowed",
            "ro.oem_unlock_supported",
        };

        for (String key : keys) {
            String val = getProp(key);
            if (val != null && !val.isEmpty()) {
                props.put(key, val);
            }
        }

        // Also check via shell if Shizuku is connected
        if (ShizukuManager.isConnected()) {
            for (String key : keys) {
                if (!props.containsKey(key)) {
                    String shellVal = shell("getprop " + key);
                    if (shellVal != null && !shellVal.isEmpty()) {
                        props.put(key, shellVal + " (shell)");
                    }
                }
            }
        }

        addSection(R.string.debug_bootloader_props, props);
    }

    // --- Kernel info ---

    private void addKernelInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("Kernel version", System.getProperty("os.version", "unknown"));
        info.put("Uptime", formatUptime());

        if (ShizukuManager.isConnected()) {
            info.put("Kernel (uname)", shell("uname -a"));
            info.put("Kernel cmdline", shell("cat /proc/cmdline 2>/dev/null | head -c 200"));
            info.put("SELinux mode", shell("getenforce"));
            info.put("SELinux boot mode", getProp("ro.boot.selinux"));
        }

        addSection(R.string.debug_kernel_info, info);
    }

    // --- OEM info ---

    private void addOemInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("OEM unlock enabled", getSetting("global", "oem_unlock_enabled"));
        info.put("Developer options", getSetting("global", "development_settings_enabled"));
        info.put("ADB enabled", getSetting("global", "adb_enabled"));
        info.put("Stay on while plugged", getSetting("global", "stay_on_while_plugged_in"));

        if (ShizukuManager.isConnected()) {
            // Samsung-specific
            String knox = shell("getprop ro.config.knox");
            if (knox != null && !knox.isEmpty()) {
                info.put("Samsung Knox", knox);
            }
            String engMode = shell("getprop ro.boot.em.mode");
            if (engMode != null && !engMode.isEmpty()) {
                info.put("Samsung EM mode", engMode);
            }
        }

        addSection(R.string.debug_oem_info, info);
    }

    // --- Root & Zygisk detection ---

    private void addRootDetection() {
        ZygiskDetector.DetectionResult detection = ZygiskDetector.detect(this);

        Map<String, String> info = new LinkedHashMap<>();
        info.put("Root framework", detection.root.label);
        if (detection.magiskVersion != null) info.put("Magisk version", detection.magiskVersion);
        if (detection.ksuVersion != null) info.put("KernelSU version", detection.ksuVersion);
        if (detection.apatchVersion != null) info.put("APatch version", detection.apatchVersion);
        info.put("Zygisk", detection.zygisk.label);
        info.put("Zygisk enabled", String.valueOf(detection.zygiskEnabled));
        info.put("Our Zygisk module", detection.ourModuleInstalled ? "installed" : "not installed");

        // Shizuku info
        info.put("Shizuku installed", String.valueOf(ShizukuManager.isInstalled(this)));
        info.put("Shizuku running", String.valueOf(ShizukuManager.isRunning()));
        info.put("Shizuku connected", String.valueOf(ShizukuManager.isConnected()));

        // Sui check
        try {
            Class<?> suiClass = Class.forName("rikka.sui.Sui");
            java.lang.reflect.Method isSui = suiClass.getMethod("isSui");
            boolean sui = (boolean) isSui.invoke(null);
            info.put("Sui (root Shizuku)", String.valueOf(sui));
        } catch (Throwable t) {
            info.put("Sui (root Shizuku)", "not available");
        }

        addSection(R.string.debug_root_detection, info);
    }

    // --- Attestation test ---

    private void addAttestationTest() {
        TextView section = createSectionHeader(getString(R.string.debug_attestation_test));
        container.addView(section);

        // Run attestation test on background thread
        new Thread(() -> {
            Map<String, String> results = new LinkedHashMap<>();
            try {
                // Check if AndroidKeyStore is available
                KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                ks.load(null);
                results.put("AndroidKeyStore", "available");

                // Check for attestation support
                try {
                    java.security.KeyPairGenerator kpg =
                        java.security.KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
                    android.security.keystore.KeyGenParameterSpec spec =
                        new android.security.keystore.KeyGenParameterSpec.Builder(
                            "bootloader_spoofer_test",
                            android.security.keystore.KeyProperties.PURPOSE_SIGN)
                            .setAttestationChallenge("test".getBytes())
                            .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                            .build();
                    kpg.initialize(spec);
                    java.security.KeyPair kp = kpg.generateKeyPair();
                    results.put("Attestation key gen", "success");

                    // Get certificate chain
                    Certificate[] chain = ks.getCertificateChain("bootloader_spoofer_test");
                    if (chain != null && chain.length > 0) {
                        results.put("Cert chain length", String.valueOf(chain.length));
                        X509Certificate leaf = (X509Certificate) chain[0];
                        results.put("Leaf issuer", leaf.getIssuerX500Principal().getName());
                        results.put("Leaf subject", leaf.getSubjectX500Principal().getName());

                        // Check for bootloader attestation extension
                        byte[] attExt = leaf.getExtensionValue("1.3.6.1.4.1.11129.2.1.17");
                        results.put("Attestation extension", attExt != null ? "present" : "absent");
                    } else {
                        results.put("Cert chain", "empty");
                    }

                    // Clean up
                    ks.deleteEntry("bootloader_spoofer_test");
                } catch (Throwable t) {
                    results.put("Attestation test", "failed: " + t.getMessage());
                }

                // Check StrongBox
                try {
                    boolean hasStrongBox = getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_STRONGBOX_KEYSTORE);
                    results.put("StrongBox", String.valueOf(hasStrongBox));
                } catch (Throwable t) {
                    results.put("StrongBox", "check failed");
                }

                // Check Key attestation feature
                try {
                    boolean hasAttest = getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_KEYSTORE_APP_ATTEST_KEY);
                    results.put("Key attestation feature", String.valueOf(hasAttest));
                } catch (Throwable t) {
                    results.put("Key attestation feature", "check failed");
                }

            } catch (Throwable t) {
                results.put("AndroidKeyStore", "failed: " + t.getMessage());
            }

            final Map<String, String> finalResults = results;
            runOnUiThread(() -> addKeyValueCard(finalResults));
        }).start();
    }

    // --- All system properties (via shell) ---

    private void addAllProps() {
        if (!ShizukuManager.isConnected()) return;

        TextView section = createSectionHeader(getString(R.string.debug_all_props));
        container.addView(section);

        new Thread(() -> {
            Map<String, String> props = new LinkedHashMap<>();
            String output = shell("getprop | grep -E 'boot|lock|verify|vbmeta|warranty|oem|knox|selinux|build.tags|build.type' 2>/dev/null");
            if (output != null && !output.isEmpty()) {
                for (String line : output.split("\n")) {
                    // Format: [key]: [value]
                    line = line.trim();
                    if (line.startsWith("[")) {
                        int closeKey = line.indexOf("]: [");
                        if (closeKey > 0) {
                            String key = line.substring(1, closeKey);
                            String val = line.substring(closeKey + 4, line.lastIndexOf("]"));
                            if (!val.isEmpty()) {
                                props.put(key, val);
                            }
                        }
                    }
                }
            }
            final Map<String, String> finalProps = props;
            runOnUiThread(() -> {
                if (finalProps.isEmpty()) {
                    TextView empty = new TextView(this);
                    empty.setText(R.string.debug_no_props);
                    empty.setPadding(32, 16, 32, 16);
                    container.addView(empty);
                } else {
                    addKeyValueCard(finalProps);
                }
            });
        }).start();
    }

    // --- UI helpers ---

    private void addSection(int titleRes, Map<String, String> data) {
        TextView header = createSectionHeader(getString(titleRes));
        container.addView(header);
        addKeyValueCard(data);
    }

    private TextView createSectionHeader(String text) {
        TextView tv = new TextView(this, null, 0, R.style.SectionHeader);
        tv.setText(text);
        return tv;
    }

    private void addKeyValueCard(Map<String, String> data) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(getColor(R.color.surface));
        card.setRadius(20f * getResources().getDisplayMetrics().density);
        card.setCardElevation(0f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(16, 8, 16, 8);
        card.setLayoutParams(lp);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(48, 32, 48, 32);

        for (Map.Entry<String, String> entry : data.entrySet()) {
            TextView key = new TextView(this);
            key.setText(entry.getKey());
            key.setTextColor(getColor(R.color.text_secondary));
            key.setTextSize(12);
            key.setPadding(0, 8, 0, 0);

            TextView val = new TextView(this);
            val.setText(entry.getValue());
            val.setTextColor(getColor(R.color.text_primary));
            val.setTextSize(13);
            val.setTypeface(android.graphics.Typeface.MONOSPACE);
            val.setPadding(0, 0, 0, 4);

            inner.addView(key);
            inner.addView(val);
        }

        card.addView(inner);
        container.addView(card);
    }

    // --- Property/setting helpers ---

    private String getProp(String key) {
        // SystemProperties is a hidden API — use reflection
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, "");
        } catch (Throwable t) {
            // Fallback: use shell if connected
            if (ShizukuManager.isConnected()) {
                return shell("getprop " + key);
            }
            return "";
        }
    }

    private String getSetting(String namespace, String key) {
        try {
            return String.valueOf(android.provider.Settings.Global.getInt(
                getContentResolver(), key, 0));
        } catch (Throwable t) {
            return "0";
        }
    }

    private String shell(String cmd) {
        return ShizukuManager.executeShell(cmd);
    }

    private String formatUptime() {
        long ms = android.os.SystemClock.elapsedRealtime();
        long s = ms / 1000;
        long m = s / 60;
        long h = m / 60;
        return h + "h " + (m % 60) + "m " + (s % 60) + "s";
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
