package com.takattowo.bootloaderspoofer;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Guides the user through checking and performing a real bootloader unlock.
 * This does NOT exploit anything — it uses legitimate OEM unlock mechanisms
 * (Developer Options → OEM unlocking toggle, then fastboot flashing unlock).
 */
public class UnlockHelperActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_unlock_helper);

        ImageButton back = findViewById(R.id.btn_back);
        back.setOnClickListener(v -> finish());

        View rowOemStatus = findViewById(R.id.row_oem_status);
        bindRow(rowOemStatus, R.drawable.ic_shield,
                getString(R.string.unlock_oem_status_title),
                getOemUnlockStatus(),
                v -> openDeveloperSettings());

        View rowGuide = findViewById(R.id.row_guide);
        bindRow(rowGuide, R.drawable.ic_info,
                getString(R.string.unlock_guide_title),
                getString(R.string.unlock_guide_subtitle),
                v -> showUnlockGuide());

        View rowFastboot = findViewById(R.id.row_fastboot);
        bindRow(rowFastboot, R.drawable.ic_tune,
                getString(R.string.unlock_fastboot_title),
                getString(R.string.unlock_fastboot_subtitle),
                v -> showFastbootInstructions());

        View rowReboot = findViewById(R.id.row_reboot);
        bindRow(rowReboot, R.drawable.ic_info,
                getString(R.string.unlock_reboot_title),
                getString(R.string.unlock_reboot_subtitle),
                v -> confirmRebootToBootloader());
    }

    private void bindRow(View row, int iconRes, String title, String subtitle, View.OnClickListener onClick) {
        ImageView icon = row.findViewById(R.id.row_icon);
        TextView t = row.findViewById(R.id.row_title);
        TextView s = row.findViewById(R.id.row_subtitle);
        icon.setImageResource(iconRes);
        t.setText(title);
        s.setText(subtitle);
        row.setOnClickListener(onClick);
    }

    /** Check Settings.Global.OEM_UNLOCK_ENABLED */
    private String getOemUnlockStatus() {
        try {
            int enabled = Settings.Global.getInt(
                    getContentResolver(), "oem_unlock_enabled", -1);
            if (enabled == 1) {
                return getString(R.string.unlock_oem_enabled);
            } else if (enabled == 0) {
                return getString(R.string.unlock_oem_disabled);
            } else {
                return getString(R.string.unlock_oem_unknown);
            }
        } catch (Throwable t) {
            return getString(R.string.unlock_oem_unknown);
        }
    }

    private void openDeveloperSettings() {
        try {
            // ACTION_DEVELOPER_SETTINGS is hidden, use the string constant
            startActivity(new Intent("com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS"));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Throwable t2) {
                toast("Cannot open developer settings");
            }
        }
    }

    private void showUnlockGuide() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.unlock_guide_title)
                .setMessage(R.string.unlock_guide_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showFastbootInstructions() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.unlock_fastboot_title)
                .setMessage(R.string.unlock_fastboot_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void confirmRebootToBootloader() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.unlock_reboot_title)
                .setMessage(R.string.unlock_reboot_confirm)
                .setPositiveButton(R.string.unlock_reboot_btn, (d, w) -> rebootToBootloader())
                .setNegativeButton(R.string.confirm_cancel, null)
                .show();
    }

    private void rebootToBootloader() {
        try {
            // Use root (if available via Magisk) to reboot to bootloader
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "reboot bootloader"});
            int code = p.waitFor();
            if (code != 0) {
                // Fall back to PowerManager
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                toast(getString(R.string.unlock_reboot_manual));
            }
        } catch (Throwable t) {
            toast(getString(R.string.unlock_reboot_manual));
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
