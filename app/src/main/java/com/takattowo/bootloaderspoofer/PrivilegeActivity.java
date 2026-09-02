package com.takattowo.bootloaderspoofer;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

/**
 * Privilege escalation activity using CVE-2024-31317 (Zygote injection).
 *
 * <p>Adapted from procdev (vortexdigitalera/procdev) — BootLoader.java and
 * ZygoteFragment.java. This activity provides a UI for:
 * <ul>
 *   <li>Checking CVE-2024-31317 vulnerability status</li>
 *   <li>Requesting Shizuku permission</li>
 *   <li>Enabling/disabling OEM unlock with system-level privileges</li>
 *   <li>Setting arbitrary system properties (ro.oem.*, etc.)</li>
 *   <li>Viewing current OEM unlock status</li>
 * </ul>
 *
 * <p>The key insight: with system (UID 1000) privileges obtained via the
 * Zygote injection, we can write to {@code ro.oem.*} properties that control
 * bootloader unlock capability — something that even root cannot easily do
 * because these properties are set by the bootloader and enforced by SELinux.
 */
public class PrivilegeActivity extends AppCompatActivity {

    private static final String TAG = "PrivilegeActivity";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView tvCveStatus;
    private TextView tvCveDetails;
    private TextView tvShizukuStatus;
    private TextView tvStatusOutput;
    private MaterialButton btnCheckCve;
    private MaterialButton btnRequestPermission;
    private MaterialButton btnEnableOemUnlock;
    private MaterialButton btnDisableOemUnlock;
    private MaterialButton btnSetProp;
    private MaterialButton btnRefreshStatus;
    private TextInputEditText etPropName;
    private TextInputEditText etPropValue;

    private boolean cveChecked = false;
    private boolean cveVulnerable = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_privilege);

        // Back button
        ImageButton back = findViewById(R.id.btn_back);
        back.setOnClickListener(v -> finish());

        // Bind views
        tvCveStatus = findViewById(R.id.tv_cve_status);
        tvCveDetails = findViewById(R.id.tv_cve_details);
        tvShizukuStatus = findViewById(R.id.tv_shizuku_status);
        tvStatusOutput = findViewById(R.id.tv_status_output);
        btnCheckCve = findViewById(R.id.btn_check_cve);
        btnRequestPermission = findViewById(R.id.btn_request_permission);
        btnEnableOemUnlock = findViewById(R.id.btn_enable_oem_unlock);
        btnDisableOemUnlock = findViewById(R.id.btn_disable_oem_unlock);
        btnSetProp = findViewById(R.id.btn_set_prop);
        btnRefreshStatus = findViewById(R.id.btn_refresh_status);
        etPropName = findViewById(R.id.et_prop_name);
        etPropValue = findViewById(R.id.et_prop_value);

        // CVE check button
        btnCheckCve.setOnClickListener(v -> checkCveVulnerability());

        // Shizuku permission button
        btnRequestPermission.setOnClickListener(v -> {
            ShizukuManager.requestPermission();
            Toast.makeText(this, R.string.privilege_permission_requested, Toast.LENGTH_SHORT).show();
        });

        // Enable OEM unlock
        btnEnableOemUnlock.setOnClickListener(v -> confirmEnableOemUnlock());

        // Disable OEM unlock
        btnDisableOemUnlock.setOnClickListener(v -> confirmDisableOemUnlock());

        // Set custom property
        btnSetProp.setOnClickListener(v -> setCustomProperty());

        // Refresh status
        btnRefreshStatus.setOnClickListener(v -> refreshStatus());

        // Initial checks
        checkShizukuStatus();
        autoCheckCve();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkShizukuStatus();
    }

    /** Check CVE-2024-31317 vulnerability status. */
    private void checkCveVulnerability() {
        btnCheckCve.setEnabled(false);
        tvCveStatus.setText(R.string.privilege_checking);
        tvCveDetails.setText("");

        PrivilegeEscalator.checkVulnerability(this, (vulnerable, details) -> {
            cveChecked = true;
            cveVulnerable = vulnerable;
            mainHandler.post(() -> {
                btnCheckCve.setEnabled(true);
                if (vulnerable) {
                    tvCveStatus.setText(R.string.privilege_cve_vulnerable);
                    tvCveStatus.setTextColor(getColor(R.color.badge_xposed_text));
                    tvCveDetails.setText(R.string.privilege_cve_vulnerable_desc);
                } else {
                    tvCveStatus.setText(R.string.privilege_cve_patched);
                    tvCveStatus.setTextColor(getColor(R.color.badge_magisk_text));
                    tvCveDetails.setText(R.string.privilege_cve_patched_desc);
                }
            });
        });
    }

    /** Auto-check CVE on first load. */
    private void autoCheckCve() {
        if (!cveChecked) {
            checkCveVulnerability();
        }
    }

    /** Check and display Shizuku status. */
    private void checkShizukuStatus() {
        boolean running = ShizukuManager.isRunning();
        boolean permission = ShizukuManager.checkPermission();

        if (!running) {
            tvShizukuStatus.setText(R.string.privilege_shizuku_not_running);
            tvShizukuStatus.setTextColor(getColor(R.color.badge_xposed_text));
            btnRequestPermission.setEnabled(false);
        } else if (!permission) {
            tvShizukuStatus.setText(R.string.privilege_shizuku_no_permission);
            tvShizukuStatus.setTextColor(getColor(R.color.badge_xposed_text));
            btnRequestPermission.setEnabled(true);
        } else {
            tvShizukuStatus.setText(R.string.privilege_shizuku_ready);
            tvShizukuStatus.setTextColor(getColor(R.color.badge_magisk_text));
            btnRequestPermission.setEnabled(false);
        }
    }

    /** Confirm before enabling OEM unlock. */
    private void confirmEnableOemUnlock() {
        if (!verifyPrerequisites()) return;

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.privilege_enable_oem_unlock)
                .setMessage(R.string.privilege_enable_confirm)
                .setPositiveButton(R.string.privilege_proceed, (d, w) -> doEnableOemUnlock())
                .setNegativeButton(R.string.confirm_cancel, null)
                .show();
    }

    /** Confirm before disabling OEM unlock. */
    private void confirmDisableOemUnlock() {
        if (!verifyPrerequisites()) return;

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.privilege_disable_oem_unlock)
                .setMessage(R.string.privilege_disable_confirm)
                .setPositiveButton(R.string.privilege_proceed, (d, w) -> doDisableOemUnlock())
                .setNegativeButton(R.string.confirm_cancel, null)
                .show();
    }

    /** Verify Shizuku and CVE prerequisites. */
    private boolean verifyPrerequisites() {
        if (!ShizukuManager.isRunning()) {
            Toast.makeText(this, R.string.privilege_shizuku_not_running, Toast.LENGTH_LONG).show();
            return false;
        }
        if (!ShizukuManager.checkPermission()) {
            Toast.makeText(this, R.string.privilege_shizuku_no_permission, Toast.LENGTH_LONG).show();
            return false;
        }
        if (!cveChecked) {
            Toast.makeText(this, R.string.privilege_check_cve_first, Toast.LENGTH_LONG).show();
            return false;
        }
        if (!cveVulnerable) {
            Toast.makeText(this, R.string.privilege_not_vulnerable, Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    /** Execute OEM unlock enable. */
    private void doEnableOemUnlock() {
        setButtonsEnabled(false);
        tvStatusOutput.setText(R.string.privilege_injecting);

        PrivilegeEscalator.enableOemUnlock(this, (success, message) -> {
            mainHandler.post(() -> {
                setButtonsEnabled(true);
                tvStatusOutput.setText(message);
                checkShizukuStatus();
                if (success) {
                    // Refresh status after a delay
                    mainHandler.postDelayed(this::refreshStatus, 1000);
                }
            });
        });
    }

    /** Execute OEM unlock disable. */
    private void doDisableOemUnlock() {
        setButtonsEnabled(false);
        tvStatusOutput.setText(R.string.privilege_injecting);

        PrivilegeEscalator.disableOemUnlock(this, (success, message) -> {
            mainHandler.post(() -> {
                setButtonsEnabled(true);
                tvStatusOutput.setText(message);
                checkShizukuStatus();
                if (success) {
                    mainHandler.postDelayed(this::refreshStatus, 1000);
                }
            });
        });
    }

    /** Set a custom system property. */
    private void setCustomProperty() {
        if (!verifyPrerequisites()) return;

        String propName = etPropName.getText() != null ? etPropName.getText().toString().trim() : "";
        String propValue = etPropValue.getText() != null ? etPropValue.getText().toString().trim() : "";

        if (propName.isEmpty()) {
            Toast.makeText(this, R.string.privilege_prop_name_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (propValue.isEmpty()) {
            Toast.makeText(this, R.string.privilege_prop_value_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        setButtonsEnabled(false);
        tvStatusOutput.setText(getString(R.string.privilege_setting_prop, propName, propValue));

        PrivilegeEscalator.setSystemProperty(this, propName, propValue, (success, message) -> {
            mainHandler.post(() -> {
                setButtonsEnabled(true);
                tvStatusOutput.setText(message);
                checkShizukuStatus();
            });
        });
    }

    /** Refresh OEM unlock status display. */
    private void refreshStatus() {
        tvStatusOutput.setText(R.string.privilege_loading_status);

        new Thread(() -> {
            String status = PrivilegeEscalator.readOemUnlockStatus();
            mainHandler.post(() -> tvStatusOutput.setText(status));
        }).start();
    }

    /** Enable/disable all action buttons. */
    private void setButtonsEnabled(boolean enabled) {
        btnEnableOemUnlock.setEnabled(enabled);
        btnDisableOemUnlock.setEnabled(enabled);
        btnSetProp.setEnabled(enabled);
        btnRefreshStatus.setEnabled(enabled);
        btnCheckCve.setEnabled(enabled);
    }
}
