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

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

/**
 * In-app module loader for Magisk / KernelSU modules.
 *
 * Scans /data/adb/modules/ for installed modules, parses each module.prop,
 * and presents a list with enable/disable toggles — similar to Magisk Manager
 * or KernelSU Manager.
 *
 * Requires Shizuku (shell uid 2000) or root to read/write the modules directory.
 * On most setups /data/adb/modules is only readable by root, so this falls back
 * to `su -c` when Shizuku alone cannot read the directory.
 */
public class ModuleLoaderActivity extends AppCompatActivity {

    private static final String MODULES_DIR = "/data/adb/modules";
    private static final int SHIZUKU_REQUEST_CODE = 1002;

    private LinearLayout modulesContainer;
    private TextView shizukuStatus;
    private View shizukuConnectRow;
    private TextView modulesSummary;

    private final List<ModuleInfo> modules = new ArrayList<>();

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(() -> {
                        toast(getString(R.string.shizuku_permission_granted));
                        updateShizukuStatus();
                        loadModules();
                    });
                } else {
                    runOnUiThread(() ->
                            toast(getString(R.string.shizuku_permission_denied)));
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_module_loader);

        ImageButton back = findViewById(R.id.btn_back);
        back.setOnClickListener(v -> finish());

        shizukuStatus = findViewById(R.id.shizuku_status);
        shizukuConnectRow = findViewById(R.id.row_shizuku_connect);
        shizukuConnectRow.setOnClickListener(v -> onShizukuConnect());

        modulesSummary = findViewById(R.id.modules_summary);
        modulesContainer = findViewById(R.id.modules_container);

        ShizukuManager.init();
        Shizuku.addRequestPermissionResultListener(permissionListener);
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
        updateShizukuStatus();
        if (ShizukuManager.isConnected()) {
            loadModules();
        }
    }

    // --- Shizuku connection ---

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
            shizukuStatus.setText(R.string.shizuku_not_connected);
            shizukuConnectRow.setVisibility(View.VISIBLE);
            shizukuConnectRow.setOnClickListener(v -> {
                ShizukuManager.requestPermission();
                toast(getString(R.string.shizuku_requesting_permission));
            });
        } else {
            shizukuStatus.setText(R.string.shizuku_connected);
            shizukuConnectRow.setVisibility(View.GONE);
        }
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

    // --- Module scanning ---

    /**
     * Scan /data/adb/modules/ on a background thread and rebuild the list.
     * Tries plain shell first; falls back to `su -c` if the directory is
     * not readable as uid 2000.
     */
    private void loadModules() {
        modulesContainer.removeAllViews();
        modulesSummary.setText(getString(R.string.modules_loading));

        new Thread(() -> {
            List<ModuleInfo> result = scanModules();
            runOnUiThread(() -> {
                modules.clear();
                modules.addAll(result);
                rebuildModuleRows();
            });
        }).start();
    }

    /**
     * Scan the modules directory. Returns a list of ModuleInfo with state flags set.
     */
    private List<ModuleInfo> scanModules() {
        List<ModuleInfo> list = new ArrayList<>();

        // List module directories (one per line)
        String listing = shell("ls -1 " + MODULES_DIR + " 2>/dev/null");
        if (listing == null || listing.isEmpty()) {
            // Fallback to root
            listing = shell("su -c 'ls -1 " + MODULES_DIR + "'");
        }
        if (listing == null || listing.isEmpty()) {
            return list;
        }

        for (String id : listing.split("\n")) {
            id = id.trim();
            if (id.isEmpty() || id.startsWith(".")) continue;

            String dir = MODULES_DIR + "/" + id;
            String prop = shell("cat " + dir + "/module.prop 2>/dev/null");
            if (prop == null || prop.isEmpty()) {
                prop = shell("su -c 'cat " + dir + "/module.prop'");
            }
            if (prop == null || prop.isEmpty()) continue;

            ModuleInfo info = ModuleInfo.fromProp(id, prop);

            // Check state marker files
            info.disabled = fileExists(dir + "/disable");
            info.removePending = fileExists(dir + "/remove");
            info.updatePending = fileExists(dir + "/update");

            list.add(info);
        }
        return list;
    }

    /**
     * Check if a file exists. Tries plain shell, then root.
     */
    private boolean fileExists(String path) {
        String r = shell("[ -f " + path + " ] && echo yes || echo no");
        if (r == null || r.isEmpty()) {
            r = shell("su -c '[ -f " + path + " ] && echo yes || echo no'");
        }
        return "yes".equals(r);
    }

    // --- UI ---

    private void rebuildModuleRows() {
        modulesContainer.removeAllViews();

        if (modules.isEmpty()) {
            modulesSummary.setText(getString(R.string.modules_empty));
            return;
        }

        int enabled = 0;
        for (ModuleInfo m : modules) {
            if (!m.disabled) enabled++;
        }
        modulesSummary.setText(getString(R.string.modules_summary,
                modules.size(), enabled));

        for (ModuleInfo m : modules) {
            addModuleRow(m);
        }
    }

    private void addModuleRow(ModuleInfo m) {
        View row = LayoutInflater.from(this).inflate(R.layout.row_module, modulesContainer, false);

        TextView name = row.findViewById(R.id.module_name);
        TextView version = row.findViewById(R.id.module_version);
        TextView author = row.findViewById(R.id.module_author);
        TextView desc = row.findViewById(R.id.module_description);
        TextView state = row.findViewById(R.id.module_state);
        Switch toggle = row.findViewById(R.id.module_switch);

        name.setText(m.name != null && !m.name.isEmpty() ? m.name : m.id);
        String verText = m.version;
        if (m.versionCode != null && !m.versionCode.isEmpty()) {
            verText = (verText != null && !verText.isEmpty())
                    ? verText + " (" + m.versionCode + ")"
                    : "v" + m.versionCode;
        }
        if (verText == null || verText.isEmpty()) verText = "";
        version.setText(verText);

        if (m.author != null && !m.author.isEmpty()) {
            author.setText(getString(R.string.module_author_label, m.author));
            author.setVisibility(View.VISIBLE);
        } else {
            author.setVisibility(View.GONE);
        }

        if (m.description != null && !m.description.isEmpty()) {
            desc.setText(m.description);
            desc.setVisibility(View.VISIBLE);
        } else {
            desc.setVisibility(View.GONE);
        }

        // State badges
        StringBuilder badge = new StringBuilder();
        if (m.removePending) {
            badge.append(getString(R.string.module_state_remove));
        }
        if (m.updatePending) {
            if (badge.length() > 0) badge.append(" · ");
            badge.append(getString(R.string.module_state_update));
        }
        if (badge.length() > 0) {
            state.setText(badge);
            state.setVisibility(View.VISIBLE);
        } else {
            state.setVisibility(View.GONE);
        }

        toggle.setChecked(!m.disabled);
        toggle.setEnabled(ShizukuManager.isConnected());

        row.setOnClickListener(v -> showModuleDialog(m, toggle));

        // Wrap in a card
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(getColor(R.color.surface));
        card.setRadius(20f * getResources().getDisplayMetrics().density);
        card.setCardElevation(0f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(16, 8, 16, 8);
        card.setLayoutParams(lp);
        card.addView(row);

        modulesContainer.addView(card);
    }

    private void showModuleDialog(ModuleInfo m, Switch toggle) {
        String[] options;
        if (m.disabled) {
            options = new String[]{
                    getString(R.string.module_action_enable),
                    getString(R.string.module_action_details)
            };
        } else {
            options = new String[]{
                    getString(R.string.module_action_disable),
                    getString(R.string.module_action_details)
            };
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(m.name != null && !m.name.isEmpty() ? m.name : m.id)
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        toggleModule(m, toggle);
                    } else {
                        showDetails(m);
                    }
                })
                .setNegativeButton(R.string.confirm_cancel, null)
                .show();
    }

    private void showDetails(ModuleInfo m) {
        StringBuilder sb = new StringBuilder();
        sb.append("ID: ").append(m.id).append("\n");
        sb.append("Name: ").append(m.name).append("\n");
        sb.append("Version: ").append(m.version).append("\n");
        sb.append("Version code: ").append(m.versionCode).append("\n");
        sb.append("Author: ").append(m.author).append("\n");
        sb.append("Description: ").append(m.description).append("\n");
        sb.append("State: ");
        if (m.disabled) sb.append(getString(R.string.module_state_disabled));
        else sb.append(getString(R.string.module_state_enabled));
        if (m.removePending) sb.append(", ").append(getString(R.string.module_state_remove));
        if (m.updatePending) sb.append(", ").append(getString(R.string.module_state_update));

        new MaterialAlertDialogBuilder(this)
                .setTitle(m.name != null && !m.name.isEmpty() ? m.name : m.id)
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void toggleModule(ModuleInfo m, Switch toggle) {
        String dir = MODULES_DIR + "/" + m.id;
        boolean success;
        if (m.disabled) {
            // Enable: remove the disable marker
            success = shellBool("rm -f " + dir + "/disable 2>/dev/null")
                    || shellBool("su -c 'rm -f " + dir + "/disable'");
            if (success) m.disabled = false;
        } else {
            // Disable: create the disable marker
            success = shellBool("touch " + dir + "/disable 2>/dev/null")
                    || shellBool("su -c 'touch " + dir + "/disable'");
            if (success) m.disabled = true;
        }
        if (success) {
            toggle.setChecked(!m.disabled);
            toast(m.name + ": " + (m.disabled
                    ? getString(R.string.module_disabled_toast)
                    : getString(R.string.module_enabled_toast)));
        } else {
            toast(getString(R.string.module_toggle_failed));
        }
    }

    // --- Shell helpers ---

    private String shell(String cmd) {
        return ShizukuManager.executeShell(cmd);
    }

    private boolean shellBool(String cmd) {
        String r = shell(cmd);
        return r != null && !r.isEmpty();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
