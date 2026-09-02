package com.takattowo.bootloaderspoofer;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Map;

import rikka.shizuku.Shizuku;

/**
 * System tweaks activity using Shizuku for elevated shell access.
 * Provides toggles for hidden system settings including OEM unlock,
 * developer options, display settings, and Samsung-specific Qualcomm tweaks.
 */
public class SystemTweaksActivity extends AppCompatActivity {

    private LinearLayout tweaksContainer;
    private TextView shizukuStatus;
    private View shizukuConnectRow;
    private View samsungStatusRow;

    private static final int SHIZUKU_REQUEST_CODE = 1001;

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(() -> {
                        toast(getString(R.string.shizuku_permission_granted));
                        updateShizukuStatus();
                        rebuildTweakRows();
                    });
                } else {
                    runOnUiThread(() ->
                            toast(getString(R.string.shizuku_permission_denied)));
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_system_tweaks);

        ImageButton back = findViewById(R.id.btn_back);
        back.setOnClickListener(v -> finish());

        shizukuStatus = findViewById(R.id.shizuku_status);
        shizukuConnectRow = findViewById(R.id.row_shizuku_connect);
        shizukuConnectRow.setOnClickListener(v -> onShizukuConnect());

        samsungStatusRow = findViewById(R.id.row_samsung_status);
        samsungStatusRow.setOnClickListener(v -> showSamsungStatus());

        tweaksContainer = findViewById(R.id.tweaks_container);

        ShizukuManager.init();
        Shizuku.addRequestPermissionResultListener(permissionListener);
        ShizukuManager.addBinderStateListener(available -> runOnUiThread(() -> {
            updateShizukuStatus();
            rebuildTweakRows();
        }));
        updateShizukuStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(permissionListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Actively request binder in case it wasn't delivered to our process
        ShizukuManager.requestBinder(this);
        updateShizukuStatus();
        rebuildTweakRows();
    }

    private void updateShizukuStatus() {
        boolean installed = ShizukuManager.isInstalled(this);
        boolean connected = ShizukuManager.isConnected();

        if (!installed) {
            shizukuStatus.setText(R.string.shizuku_not_installed);
            shizukuConnectRow.setVisibility(View.VISIBLE);
            shizukuConnectRow.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://shizuku.plus")));
                } catch (Throwable t) {
                    toast("Cannot open browser");
                }
            });
        } else if (!connected) {
            boolean running = ShizukuManager.isRunning();
            if (!running) {
                shizukuStatus.setText(R.string.shizuku_not_running);
                shizukuConnectRow.setVisibility(View.VISIBLE);
                shizukuConnectRow.setOnClickListener(v -> {
                    try {
                        Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                        if (launch != null) startActivity(launch);
                        else toast("Shizuku not installed");
                    } catch (Throwable t2) {
                        toast("Cannot open Shizuku");
                    }
                });
                // Re-check after a delay — binder may arrive asynchronously
                getWindow().getDecorView().postDelayed(() -> {
                    if (ShizukuManager.isRunning()) {
                        updateShizukuStatus();
                        rebuildTweakRows();
                    }
                }, 2000);
            } else {
                shizukuStatus.setText(R.string.shizuku_not_granted);
                shizukuConnectRow.setVisibility(View.VISIBLE);
                shizukuConnectRow.setOnClickListener(v -> {
                    ShizukuManager.requestPermission();
                    toast(getString(R.string.shizuku_requesting_permission));
                });
            }
        } else {
            shizukuStatus.setText(R.string.shizuku_connected);
            shizukuConnectRow.setVisibility(View.GONE);
        }
    }

    private void rebuildTweakRows() {
        tweaksContainer.removeAllViews();

        if (!ShizukuManager.isConnected()) {
            TextView hint = new TextView(this);
            hint.setText(R.string.tweaks_connect_hint);
            hint.setPadding(32, 32, 32, 32);
            tweaksContainer.addView(hint);
            return;
        }

        // Add section headers and tweak rows
        addSectionHeader(R.string.tweaks_section_bootloader);
        addTweakRow(ShizukuManager.TWEAKS.get("oem_unlock"));
        addTweakRow(ShizukuManager.TWEAKS.get("oem_unlock_samsung"));

        addSectionHeader(R.string.tweaks_section_developer);
        addTweakRow(ShizukuManager.TWEAKS.get("adb_enabled"));
        addTweakRow(ShizukuManager.TWEAKS.get("adb_wifi_enabled"));
        addTweakRow(ShizukuManager.TWEAKS.get("development_settings_enabled"));
        addTweakRow(ShizukuManager.TWEAKS.get("stay_on_while_plugged_in"));

        addSectionHeader(R.string.tweaks_section_display);
        addTweakRow(ShizukuManager.TWEAKS.get("screen_brightness_mode"));
        addTweakRow(ShizukuManager.TWEAKS.get("screen_off_timeout"));
        addTweakRow(ShizukuManager.TWEAKS.get("window_animation_scale"));
        addTweakRow(ShizukuManager.TWEAKS.get("transition_animation_scale"));
        addTweakRow(ShizukuManager.TWEAKS.get("animator_duration_scale"));

        addSectionHeader(R.string.tweaks_section_samsung);
        addTweakRow(ShizukuManager.TWEAKS.get("samsung_knox_warranty"));
        addTweakRow(ShizukuManager.TWEAKS.get("samsung_eng_mode"));
        addTweakRow(ShizukuManager.TWEAKS.get("samsung_usb_config"));
        addTweakRow(ShizukuManager.TWEAKS.get("verified_boot_state"));
        addTweakRow(ShizukuManager.TWEAKS.get("flash_locked"));
    }

    private void addSectionHeader(int textRes) {
        TextView header = new TextView(this, null, 0, R.style.SectionHeader);
        header.setText(textRes);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tweaksContainer.addView(header, lp);
    }

    private void addTweakRow(ShizukuManager.Tweak tweak) {
        if (tweak == null) return;
        View row = LayoutInflater.from(this).inflate(R.layout.row_tweak, tweaksContainer, false);
        TextView title = row.findViewById(R.id.tweak_title);
        TextView desc = row.findViewById(R.id.tweak_desc);
        Switch toggle = row.findViewById(R.id.tweak_switch);

        title.setText(tweak.title);
        desc.setText(tweak.description);

        boolean enabled = ShizukuManager.isTweakEnabled(tweak);
        toggle.setChecked(enabled);
        toggle.setEnabled(ShizukuManager.isConnected());

        toggle.setOnCheckedChangeListener((button, isChecked) -> {
            boolean success = isChecked
                    ? ShizukuManager.enableTweak(tweak)
                    : ShizukuManager.disableTweak(tweak);
            if (success) {
                toast(tweak.title + ": " + (isChecked ? "ON" : "OFF"));
            } else {
                toast(tweak.title + ": failed");
                toggle.setChecked(!isChecked); // revert
            }
        });

        tweaksContainer.addView(row);
    }

    private void showSamsungStatus() {
        if (!ShizukuManager.isConnected()) {
            toast(getString(R.string.shizuku_not_connected));
            return;
        }
        new Thread(() -> {
            String status = ShizukuManager.checkSamsungBootloaderStatus();
            runOnUiThread(() ->
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.samsung_status_title)
                            .setMessage(status)
                            .setPositiveButton(android.R.string.ok, null)
                            .show());
        }).start();
    }

    private void onShizukuConnect() {
        if (!ShizukuManager.isInstalled(this)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://shizuku.plus")));
            } catch (Throwable t) {
                toast("Cannot open browser");
            }
        } else {
            ShizukuManager.requestPermission();
            toast(getString(R.string.shizuku_requesting_permission));
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
