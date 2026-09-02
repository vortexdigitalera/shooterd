package com.takattowo.bootloaderspoofer;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.Arrays;

import io.github.libxposed.service.XposedService;

public class MainActivity extends AppCompatActivity implements App.ServiceStateListener {

    private static final String LAUNCHER_ALIAS = "com.takattowo.bootloaderspoofer.LauncherAlias";

    private MaterialCardView statusCard;
    private View statusIconBg;
    private ImageView statusIcon;
    private TextView statusTitle;
    private TextView statusSubtitle;

    private LinearLayout rowMode;
    private TextView rowModeValue;

    private LinearLayout rowBootstate;
    private TextView rowBootstateValue;

    private LinearLayout rowSpoofscope;
    private TextView rowSpoofscopeValue;

    private LinearLayout rowZygisk;
    private TextView rowZygiskValue;

    private View rowHideIcon;
    private MaterialSwitch hideIconSwitch;
    private TextView hideIconSubtitle;

    private View rowAbout;
    private View rowAdvanced;

    private volatile XposedService service;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusCard = findViewById(R.id.status_card);
        statusIconBg = findViewById(R.id.status_icon_bg);
        statusIcon = findViewById(R.id.status_icon);
        statusTitle = findViewById(R.id.status_title);
        statusSubtitle = findViewById(R.id.status_subtitle);

        rowMode = findViewById(R.id.row_mode);
        rowModeValue = findViewById(R.id.row_mode_value);
        rowMode.setOnClickListener(v -> showModeDialog());

        rowBootstate = findViewById(R.id.row_bootstate);
        rowBootstateValue = findViewById(R.id.row_bootstate_value);
        rowBootstate.setOnClickListener(v -> onBootstateTapped());

        rowSpoofscope = findViewById(R.id.row_spoofscope);
        rowSpoofscopeValue = findViewById(R.id.row_spoofscope_value);
        rowSpoofscope.setOnClickListener(v -> onSpoofscopeTapped());

        rowZygisk = findViewById(R.id.row_zygisk);
        rowZygiskValue = findViewById(R.id.row_zygisk_value);
        rowZygisk.setOnClickListener(v -> onZygiskTapped());

        rowHideIcon = findViewById(R.id.row_hide_icon);
        hideIconSwitch = findViewById(R.id.row_hide_icon_switch);
        hideIconSubtitle = findViewById(R.id.row_hide_icon_subtitle);
        refreshHideIconUI();
        rowHideIcon.setOnClickListener(v -> onHideIconTapped());

        rowAbout = findViewById(R.id.row_about);
        bindRow(rowAbout, R.drawable.ic_info,
                getString(R.string.row_about_title),
                buildVersionString(),
                v -> startActivity(new Intent(this, AboutActivity.class)));

        rowAdvanced = findViewById(R.id.row_advanced);
        bindRow(rowAdvanced, R.drawable.ic_shield,
                getString(R.string.row_advanced_title),
                getString(R.string.row_advanced_subtitle),
                v -> startActivity(new Intent(this, AdvancedActivity.class)));

        View rowUnlock = findViewById(R.id.row_unlock);
        bindRow(rowUnlock, R.drawable.ic_shield,
                getString(R.string.row_unlock_title),
                getString(R.string.row_unlock_subtitle),
                v -> startActivity(new Intent(this, UnlockHelperActivity.class)));

        View rowTweaks = findViewById(R.id.row_tweaks);
        bindRow(rowTweaks, R.drawable.ic_tune,
                getString(R.string.row_tweaks_title),
                getString(R.string.row_tweaks_subtitle),
                v -> startActivity(new Intent(this, SystemTweaksActivity.class)));

        View rowModules = findViewById(R.id.row_modules);
        bindRow(rowModules, R.drawable.ic_file,
                getString(R.string.row_modules_title),
                getString(R.string.row_modules_subtitle),
                v -> startActivity(new Intent(this, ModuleLoaderActivity.class)));

        View rowDebug = findViewById(R.id.row_debug);
        bindRow(rowDebug, R.drawable.ic_bug,
                getString(R.string.row_debug_title),
                getString(R.string.row_debug_subtitle),
                v -> startActivity(new Intent(this, DebugInfoActivity.class)));

        View rowMiniProot = findViewById(R.id.row_miniproot);
        bindRow(rowMiniProot, R.drawable.ic_shield,
                getString(R.string.row_miniproot_title),
                getString(R.string.row_miniproot_subtitle),
                v -> startActivity(new Intent(this, MiniProotActivity.class)));
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

    @Override
    protected void onStart() {
        super.onStart();
        App.addServiceStateListener(this, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDynamic();
        refreshHideIconUI();
    }

    @Override
    protected void onStop() {
        App.removeServiceStateListener(this);
        super.onStop();
    }

    @Override
    public void onServiceStateChanged(XposedService svc) {
        service = svc;
        runOnUiThread(this::refreshDynamic);
    }

    private void refreshDynamic() {
        updateStatusCard();
        updateModeRow();
        updateBootstateRow();
        updateSpoofscopeRow();
        updateZygiskRow();
    }

    private void updateStatusCard() {
        XposedService svc = service;
        if (svc == null) {
            statusCard.setCardBackgroundColor(getColor(R.color.status_card_bg_inactive));
            statusIconBg.setBackgroundResource(R.drawable.bg_icon_circle_status_inactive);
            statusIcon.setImageResource(R.drawable.ic_warning);
            statusTitle.setText(R.string.status_disabled);
            statusSubtitle.setText(R.string.status_disabled_sub);
            return;
        }
        statusCard.setCardBackgroundColor(getColor(R.color.status_card_bg));
        statusIconBg.setBackgroundResource(R.drawable.bg_icon_circle_status);
        statusIcon.setImageResource(R.drawable.ic_check);
        statusTitle.setText(R.string.status_enabled);

        String framework;
        try {
            framework = svc.getFrameworkName() + " " + svc.getFrameworkVersion();
        } catch (Throwable t) {
            framework = "framework attached";
        }
        statusSubtitle.setText(framework + "\nKeybox: " + keyboxSourceLabel());
    }

    private String keyboxSourceLabel() {
        String xml = readRemoteString(Config.KEYBOX_FILE);
        try {
            KeyboxLoader.Result r = KeyboxLoader.loadFromXmlOrBundled(xml);
            String source = r.userEC && r.userRSA ? "user"
                    : r.userEC || r.userRSA ? "user + bundled" : "bundled (AOSP)";
            return r.ecExpired || r.rsaExpired ? source + " (expired)" : source;
        } catch (Throwable ignored) {
        }
        return "bundled (AOSP)";
    }

    private void updateModeRow() {
        String mode = currentMode();
        int titleRes;
        switch (mode) {
            case Config.MODE_CERT_GENERATE: titleRes = R.string.mode_cert_generate_title; break;
            case Config.MODE_OFF:           titleRes = R.string.mode_off_title; break;
            default:                        titleRes = R.string.mode_leaf_hack_title;
        }
        rowModeValue.setText(getString(titleRes));
    }

    private String currentMode() {
        if (service == null) return Config.MODE_LEAF_HACK;
        return Config.normalizeMode(readRemoteString(Config.MODE_FILE));
    }

    private void updateBootstateRow() {
        String state = currentBootState();
        rowBootstateValue.setText(getString(
                Config.BOOTSTATE_UNLOCKED.equals(state)
                        ? R.string.bootstate_unlocked
                        : R.string.bootstate_locked));
    }

    private String currentBootState() {
        if (service == null) return Config.BOOTSTATE_LOCKED;
        return Config.normalizeBootState(readRemoteString(Config.BOOTSTATE_FILE));
    }

    private void onBootstateTapped() {
        if (service == null) { toast(getString(R.string.toast_not_connected)); return; }
        String current = currentBootState();
        String next = Config.BOOTSTATE_LOCKED.equals(current)
                ? Config.BOOTSTATE_UNLOCKED : Config.BOOTSTATE_LOCKED;
        if (writeRemoteString(Config.BOOTSTATE_FILE, next)) {
            updateBootstateRow();
            toast(getString(R.string.toast_restart_required));
        }
    }

    // --- Spoof scope ---

    private void updateSpoofscopeRow() {
        String scope = currentSpoofScope();
        rowSpoofscopeValue.setText(getString(
                Config.SCOPE_GLOBAL.equals(scope)
                        ? R.string.spoofscope_global
                        : R.string.spoofscope_scoped));
    }

    private String currentSpoofScope() {
        if (service == null) return Config.SCOPE_SCOPED;
        return Config.normalizeSpoofScope(readRemoteString(Config.SPOOFSCOPE_FILE));
    }

    private void onSpoofscopeTapped() {
        if (service == null) { toast(getString(R.string.toast_not_connected)); return; }
        String current = currentSpoofScope();
        if (Config.SCOPE_SCOPED.equals(current)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.row_spoofscope_title)
                    .setMessage(R.string.spoofscope_global_warn)
                    .setPositiveButton(R.string.spoofscope_global, (d, w) -> {
                        writeRemoteString(Config.SPOOFSCOPE_FILE, Config.SCOPE_GLOBAL);
                        updateSpoofscopeRow();
                        toast(getString(R.string.toast_restart_required));
                    })
                    .setNegativeButton(R.string.confirm_cancel, null)
                    .show();
        } else {
            writeRemoteString(Config.SPOOFSCOPE_FILE, Config.SCOPE_SCOPED);
            updateSpoofscopeRow();
            toast(getString(R.string.toast_restart_required));
        }
    }

    // --- Zygisk mode ---

    private void updateZygiskRow() {
        String zygisk = currentZygiskMode();
        int res;
        switch (zygisk) {
            case Config.ZYGISK_PASSIVE: res = R.string.zygisk_passive; break;
            case Config.ZYGISK_ACTIVE:  res = R.string.zygisk_active; break;
            default:                   res = R.string.zygisk_off; break;
        }
        // Append detected Zygisk implementation
        ZygiskDetector.DetectionResult detection = ZygiskDetector.detect(this);
        String detected = "";
        if (detection.hasZygisk()) {
            detected = " [" + detection.zygisk.label + "]";
        } else if (detection.hasRoot()) {
            detected = " [root: " + detection.root.label + ", no Zygisk]";
        }
        rowZygiskValue.setText(getString(res) + detected);
    }

    private String currentZygiskMode() {
        if (service == null) return Config.ZYGISK_OFF;
        return Config.normalizeZygiskMode(readRemoteString(Config.ZYGISK_FILE));
    }

    private void onZygiskTapped() {
        if (service == null) { toast(getString(R.string.toast_not_connected)); return; }
        String current = currentZygiskMode();
        String next;
        switch (current) {
            case Config.ZYGISK_OFF:      next = Config.ZYGISK_PASSIVE; break;
            case Config.ZYGISK_PASSIVE:  next = Config.ZYGISK_ACTIVE; break;
            default:                    next = Config.ZYGISK_OFF; break;
        }
        if (writeRemoteString(Config.ZYGISK_FILE, next)) {
            updateZygiskRow();
            toast(getString(R.string.toast_restart_required));
        }
    }

    private void showModeDialog() {
        if (service == null) { toast(getString(R.string.toast_not_connected)); return; }

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_mode, null, false);
        final RadioButton rLeaf = view.findViewById(R.id.mode_radio_leaf);
        final RadioButton rCert = view.findViewById(R.id.mode_radio_cert);
        final RadioButton rOff  = view.findViewById(R.id.mode_radio_off);

        final String[] modes = {Config.MODE_LEAF_HACK, Config.MODE_CERT_GENERATE, Config.MODE_OFF};
        int currentIdx = Arrays.asList(modes).indexOf(currentMode());
        if (currentIdx < 0) currentIdx = 0;
        rLeaf.setChecked(currentIdx == 0);
        rCert.setChecked(currentIdx == 1);
        rOff.setChecked(currentIdx == 2);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.row_mode_title)
                .setView(view)
                .setNegativeButton(R.string.confirm_cancel, null)
                .create();

        View.OnClickListener pick = v -> {
            String chosen;
            if (v.getId() == R.id.mode_row_leaf)      { chosen = Config.MODE_LEAF_HACK; }
            else if (v.getId() == R.id.mode_row_cert) { chosen = Config.MODE_CERT_GENERATE; }
            else                                      { chosen = Config.MODE_OFF; }
            if (writeRemoteString(Config.MODE_FILE, chosen)) {
                updateModeRow();
                toast(getString(R.string.toast_restart_required));
                dialog.dismiss();
            }
        };
        view.findViewById(R.id.mode_row_leaf).setOnClickListener(pick);
        view.findViewById(R.id.mode_row_cert).setOnClickListener(pick);
        view.findViewById(R.id.mode_row_off).setOnClickListener(pick);

        dialog.show();
    }

    // --- Hide app icon ---

    private void refreshHideIconUI() {
        boolean hidden = isLauncherAliasDisabled();
        hideIconSwitch.setChecked(hidden);
        hideIconSubtitle.setText(getString(
                hidden ? R.string.row_hide_icon_on : R.string.row_hide_icon_off));
    }

    private void onHideIconTapped() {
        boolean currentlyHidden = isLauncherAliasDisabled();
        if (!currentlyHidden) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.row_hide_icon_warn_title)
                    .setMessage(R.string.row_hide_icon_warn_msg)
                    .setPositiveButton(R.string.row_hide_icon_warn_ok, (d, w) -> setLauncherAliasEnabled(false))
                    .setNegativeButton(R.string.confirm_cancel, null)
                    .show();
        } else {
            setLauncherAliasEnabled(true);
        }
    }

    private boolean isLauncherAliasDisabled() {
        try {
            ComponentName cn = new ComponentName(this, LAUNCHER_ALIAS);
            int state = getPackageManager().getComponentEnabledSetting(cn);
            return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void setLauncherAliasEnabled(boolean enabled) {
        try {
            ComponentName cn = new ComponentName(this, LAUNCHER_ALIAS);
            int state = enabled
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            getPackageManager().setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP);
            toast(getString(enabled ? R.string.toast_icon_visible : R.string.toast_icon_hidden));
        } catch (Throwable t) {
            toast("Failed: " + t.getMessage());
        }
        refreshHideIconUI();
    }

    private String buildVersionString() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "v" + info.versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    // --- remote file helpers ---

    private String readRemoteString(String name) {
        XposedService svc = service;
        if (svc == null) return null;
        try {
            return ServiceFiles.readString(svc, name, KeyboxLoader.MAX_XML_BYTES);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean writeRemoteString(String name, String content) {
        XposedService svc = service;
        if (svc == null) { toast(getString(R.string.toast_not_connected)); return false; }
        try {
            ServiceFiles.replaceString(svc, name, content, 1024);
            return true;
        } catch (Throwable t) {
            toast("Write failed: " + t.getMessage());
            return false;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
