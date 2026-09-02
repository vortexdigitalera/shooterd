package com.takattowo.bootloaderspoofer;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;

/**
 * In-app module loader for Magisk/KernelSU directory modules AND installed
 * Xposed module APKs — similar to Magisk Manager, KernelSU Manager, and
 * LSPatch's module management.
 *
 * <p>Scans two sources:
 * <ol>
 *   <li>{@code /data/adb/modules/} — Magisk/KernelSU directory modules (batched shell scan)</li>
 *   <li>Installed APKs with Xposed init files (like LSPatch's LSPPackageManager)</li>
 * </ol>
 *
 * <p>Features: enable/disable, remove, per-app scope editing, search/filter,
 * pull-to-refresh, module icons, rich details dialog, structured error reporting.
 */
public class ModuleLoaderActivity extends AppCompatActivity {

    private static final String MODULES_DIR = "/data/adb/modules";

    private LinearLayout modulesContainer;
    private TextView shizukuStatus;
    private View shizukuConnectRow;
    private TextView modulesSummary;
    private EditText searchInput;
    private ImageButton refreshBtn;

    private final List<ModuleInfo> allModules = new ArrayList<>();
    private final List<ModuleInfo> filteredModules = new ArrayList<>();
    private String searchQuery = "";

    private ModuleScanner scanner;

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

        refreshBtn = findViewById(R.id.btn_refresh);
        refreshBtn.setOnClickListener(v -> loadModules());

        shizukuStatus = findViewById(R.id.shizuku_status);
        shizukuConnectRow = findViewById(R.id.row_shizuku_connect);
        shizukuConnectRow.setOnClickListener(v -> onShizukuConnect());

        modulesSummary = findViewById(R.id.modules_summary);
        modulesContainer = findViewById(R.id.modules_container);

        searchInput = findViewById(R.id.search_input);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                searchQuery = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });

        scanner = new ModuleScanner(cmd -> ShizukuManager.executeShell(cmd));

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

    private void loadModules() {
        modulesContainer.removeAllViews();
        modulesSummary.setText(getString(R.string.modules_loading));

        new Thread(() -> {
            List<ModuleInfo> result = new ArrayList<>();

            // Scan Magisk directory modules (batched)
            ModuleScanner.ScanResult magiskResult = scanner.scanMagiskModules();
            result.addAll(magiskResult.modules);

            // Scan installed Xposed APKs
            ModuleScanner.ScanResult xposedResult = scanner.scanXposedApks(getPackageManager());
            result.addAll(xposedResult.modules);

            // Determine error state
            String error = null;
            if (result.isEmpty()) {
                if (magiskResult.hasError()) {
                    error = magiskResult.error.detail;
                } else if (xposedResult.hasError()) {
                    error = xposedResult.error.detail;
                }
            }

            final String errorMsg = error;
            runOnUiThread(() -> {
                allModules.clear();
                allModules.addAll(result);
                applyFilter();
                if (errorMsg != null && allModules.isEmpty()) {
                    modulesSummary.setText(getString(R.string.module_error_scan_failed, errorMsg));
                }
            });
        }).start();
    }

    // --- Filtering ---

    private void applyFilter() {
        filteredModules.clear();
        if (searchQuery.isEmpty()) {
            filteredModules.addAll(allModules);
        } else {
            for (ModuleInfo m : allModules) {
                String name = (m.name != null ? m.name : m.id).toLowerCase(Locale.ROOT);
                String id = (m.id != null ? m.id : "").toLowerCase(Locale.ROOT);
                String pkg = (m.packageName != null ? m.packageName : "").toLowerCase(Locale.ROOT);
                if (name.contains(searchQuery) || id.contains(searchQuery) || pkg.contains(searchQuery)) {
                    filteredModules.add(m);
                }
            }
        }
        rebuildModuleRows();
    }

    // --- UI ---

    private void rebuildModuleRows() {
        modulesContainer.removeAllViews();

        if (filteredModules.isEmpty()) {
            if (allModules.isEmpty()) {
                modulesSummary.setText(getString(R.string.modules_empty_all));
            } else {
                modulesSummary.setText(getString(R.string.modules_no_match));
            }
            return;
        }

        int enabled = 0;
        for (ModuleInfo m : filteredModules) {
            if (!m.disabled) enabled++;
        }
        modulesSummary.setText(getString(R.string.modules_summary,
                filteredModules.size(), enabled));

        for (ModuleInfo m : filteredModules) {
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
        TextView sourceBadge = row.findViewById(R.id.module_source_badge);
        ImageView icon = row.findViewById(R.id.module_icon);
        View iconBg = row.findViewById(R.id.module_icon_bg);
        Switch toggle = row.findViewById(R.id.module_switch);

        name.setText(m.name != null && !m.name.isEmpty() ? m.name : m.id);

        // Version + API badge
        String verText = m.version;
        if (m.versionCode != null && !m.versionCode.isEmpty()) {
            verText = (verText != null && !verText.isEmpty())
                    ? verText + " (" + m.versionCode + ")"
                    : "v" + m.versionCode;
        }
        if (m.source == ModuleInfo.Source.XPOSED_APK && m.xposedApi > 0) {
            String apiBadge = getString(R.string.module_api_badge, m.xposedApi);
            verText = (verText != null && !verText.isEmpty())
                    ? verText + " · " + apiBadge : apiBadge;
        }
        if (verText == null || verText.isEmpty()) verText = "";
        version.setText(verText);

        // Author
        if (m.author != null && !m.author.isEmpty()) {
            author.setText(getString(R.string.module_author_label, m.author));
            author.setVisibility(View.VISIBLE);
        } else if (m.source == ModuleInfo.Source.XPOSED_APK && m.packageName != null) {
            author.setText(m.packageName);
            author.setVisibility(View.VISIBLE);
        } else {
            author.setVisibility(View.GONE);
        }

        // Description
        if (m.description != null && !m.description.isEmpty()) {
            desc.setText(m.description);
            desc.setVisibility(View.VISIBLE);
        } else {
            desc.setVisibility(View.GONE);
        }

        // Source badge
        if (m.source == ModuleInfo.Source.MAGISK_DIR) {
            sourceBadge.setText(R.string.module_source_magisk);
            sourceBadge.setBackgroundColor(getColor(R.color.badge_magisk_bg));
            sourceBadge.setTextColor(getColor(R.color.badge_magisk_text));
            sourceBadge.setVisibility(View.VISIBLE);
        } else {
            sourceBadge.setText(R.string.module_source_xposed);
            sourceBadge.setBackgroundColor(getColor(R.color.badge_xposed_bg));
            sourceBadge.setTextColor(getColor(R.color.badge_xposed_text));
            sourceBadge.setVisibility(View.VISIBLE);
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
            if (m.removePending) {
                state.setBackgroundColor(getColor(R.color.badge_state_remove_bg));
                state.setTextColor(getColor(R.color.badge_state_remove_text));
            } else if (m.updatePending) {
                state.setBackgroundColor(getColor(R.color.badge_state_update_bg));
                state.setTextColor(getColor(R.color.badge_state_update_text));
            }
        } else {
            state.setVisibility(View.GONE);
        }

        // Icon
        loadModuleIcon(m, icon, iconBg);

        // Toggle
        toggle.setChecked(!m.disabled);
        toggle.setEnabled(ShizukuManager.isConnected() || m.source == ModuleInfo.Source.XPOSED_APK);

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

    private void loadModuleIcon(ModuleInfo m, ImageView iconView, View iconBg) {
        // For Xposed APK modules, use the app icon
        if (m.source == ModuleInfo.Source.XPOSED_APK && m.packageName != null) {
            try {
                Drawable d = getPackageManager().getApplicationIcon(m.packageName);
                iconView.setImageDrawable(d);
                iconBg.setVisibility(View.VISIBLE);
                return;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }

        // For Magisk modules, try loading icon.png from the module directory
        if (m.iconPath != null) {
            new Thread(() -> {
                Bitmap bmp = loadIconBitmap(m.iconPath);
                if (bmp != null) {
                    runOnUiThread(() -> {
                        iconView.setImageBitmap(bmp);
                        iconBg.setVisibility(View.VISIBLE);
                    });
                }
            }).start();
        }
    }

    private Bitmap loadIconBitmap(String path) {
        try {
            // Try reading via shell to a temp file, then decode
            String base64 = shell("base64 " + path + " 2>/dev/null");
            if (base64 == null || base64.isEmpty()) {
                base64 = shell("su -c 'base64 " + path + "'");
            }
            if (base64 == null || base64.isEmpty()) return null;

            byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable t) {
            return null;
        }
    }

    // --- Module actions dialog ---

    private void showModuleDialog(ModuleInfo m, Switch toggle) {
        List<String> options = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (m.disabled) {
            options.add(getString(R.string.module_action_enable));
            actions.add(() -> toggleModule(m, toggle));
        } else {
            options.add(getString(R.string.module_action_disable));
            actions.add(() -> toggleModule(m, toggle));
        }

        if (m.source == ModuleInfo.Source.MAGISK_DIR) {
            options.add(getString(R.string.module_action_remove));
            actions.add(() -> confirmRemove(m));
        }

        if (m.source == ModuleInfo.Source.XPOSED_APK) {
            if (m.xposedScope.isEmpty()) {
                options.add(getString(R.string.module_action_edit_scope));
                actions.add(() -> editScope(m));
            }
            if (m.settingsActivity != null) {
                options.add(getString(R.string.module_action_open_settings));
                actions.add(() -> openModuleSettings(m));
            }
        }

        options.add(getString(R.string.module_action_details));
        actions.add(() -> showDetails(m));

        String[] opts = options.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
                .setTitle(m.name != null && !m.name.isEmpty() ? m.name : m.id)
                .setItems(opts, (d, which) -> actions.get(which).run())
                .setNegativeButton(R.string.confirm_cancel, null)
                .show();
    }

    // --- Details dialog ---

    private void showDetails(ModuleInfo m) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.module_details_id)).append(": ").append(m.id).append("\n");
        sb.append(getString(R.string.module_details_name)).append(": ").append(m.name).append("\n");
        sb.append(getString(R.string.module_details_version)).append(": ").append(m.version).append("\n");
        sb.append(getString(R.string.module_details_version_code)).append(": ").append(m.versionCode).append("\n");
        if (m.author != null && !m.author.isEmpty()) {
            sb.append(getString(R.string.module_details_author)).append(": ").append(m.author).append("\n");
        }
        if (m.description != null && !m.description.isEmpty()) {
            sb.append(getString(R.string.module_details_description)).append(": ").append(m.description).append("\n");
        }
        sb.append(getString(R.string.module_details_source)).append(": ");
        sb.append(m.source == ModuleInfo.Source.MAGISK_DIR
                ? getString(R.string.module_source_magisk)
                : getString(R.string.module_source_xposed)).append("\n");
        if (m.path != null) {
            sb.append(getString(R.string.module_details_path)).append(": ").append(m.path).append("\n");
        }
        sb.append(getString(R.string.module_details_state)).append(": ");
        if (m.disabled) sb.append(getString(R.string.module_state_disabled));
        else sb.append(getString(R.string.module_state_enabled));
        if (m.removePending) sb.append(", ").append(getString(R.string.module_state_remove));
        if (m.updatePending) sb.append(", ").append(getString(R.string.module_state_update));
        sb.append("\n");

        if (m.source == ModuleInfo.Source.XPOSED_APK) {
            if (m.xposedApi > 0) {
                sb.append(getString(R.string.module_details_api)).append(": ").append(m.xposedApi).append("\n");
            }
            if (m.packageName != null) {
                sb.append("Package: ").append(m.packageName).append("\n");
            }
            sb.append(getString(R.string.module_details_scope)).append(": ");
            if (m.xposedScope.isEmpty()) {
                sb.append(getString(R.string.module_details_scope_global));
            } else {
                sb.append(getString(R.string.module_details_scope_apps, m.xposedScope.size())).append("\n");
                for (String pkg : m.xposedScope) {
                    sb.append("  • ").append(pkg).append("\n");
                }
            }
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(m.name != null && !m.name.isEmpty() ? m.name : m.id)
                .setMessage(sb.toString().trim())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // --- Enable / Disable ---

    private void toggleModule(ModuleInfo m, Switch toggle) {
        if (m.source == ModuleInfo.Source.XPOSED_APK) {
            // Xposed APK modules can't be toggled via marker files
            // They would need to be enabled/disabled via the Xposed framework
            toast("Xposed module toggle requires framework support");
            return;
        }
        String dir = MODULES_DIR + "/" + m.id;
        boolean success;
        if (m.disabled) {
            success = shellBool("rm -f " + dir + "/disable 2>/dev/null")
                    || shellBool("su -c 'rm -f " + dir + "/disable'");
            if (success) m.disabled = false;
        } else {
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

    // --- Remove (uninstall) ---

    private void confirmRemove(ModuleInfo m) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.module_remove_confirm_title)
                .setMessage(getString(R.string.module_remove_confirm_msg))
                .setPositiveButton(R.string.module_remove_confirm_ok, (d, w) -> removeModule(m))
                .setNegativeButton(R.string.confirm_cancel, null)
                .show();
    }

    private void removeModule(ModuleInfo m) {
        String dir = MODULES_DIR + "/" + m.id;
        boolean success = shellBool("touch " + dir + "/remove 2>/dev/null")
                || shellBool("su -c 'touch " + dir + "/remove'");
        if (success) {
            m.removePending = true;
            toast(m.name + ": " + getString(R.string.module_removed_toast));
            rebuildModuleRows();
        } else {
            toast(getString(R.string.module_remove_failed));
        }
    }

    // --- Scope editing ---

    private void editScope(ModuleInfo m) {
        // Get list of installed apps
        List<String> appLabels = new ArrayList<>();
        List<String> appPackages = new ArrayList<>();
        try {
            List<ApplicationInfo> apps = getPackageManager().getInstalledApplications(0);
            for (ApplicationInfo app : apps) {
                if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                    continue;
                }
                appLabels.add(getPackageManager().getApplicationLabel(app).toString());
                appPackages.add(app.packageName);
            }
        } catch (Throwable t) {
            toast(getString(R.string.module_scope_no_apps));
            return;
        }

        // Sort alphabetically
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < appLabels.size(); i++) indices.add(i);
        Collections.sort(indices, (a, b) -> appLabels.get(a).compareToIgnoreCase(appLabels.get(b)));

        String[] labels = new String[appLabels.size()];
        boolean[] checked = new boolean[appLabels.size()];
        for (int i = 0; i < indices.size(); i++) {
            int idx = indices.get(i);
            labels[i] = appLabels.get(idx) + "\n" + appPackages.get(idx);
            // Check if already in scope
            checked[i] = m.xposedScope.contains(appPackages.get(idx));
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.module_scope_title, m.name))
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                    int pkgIdx = indices.get(which);
                    String pkg = appPackages.get(pkgIdx);
                    if (isChecked) {
                        if (!m.xposedScope.contains(pkg)) m.xposedScope.add(pkg);
                    } else {
                        m.xposedScope.remove(pkg);
                    }
                })
                .setPositiveButton(R.string.btn_save, (d, w) -> {
                    // Write scope to module directory
                    saveScope(m);
                })
                .setNegativeButton(R.string.confirm_cancel, null)
                .show();
    }

    private void saveScope(ModuleInfo m) {
        if (m.source != ModuleInfo.Source.MAGISK_DIR) {
            toast(getString(R.string.module_scope_save_failed));
            return;
        }
        String dir = MODULES_DIR + "/" + m.id;
        StringBuilder scopeContent = new StringBuilder();
        for (String pkg : m.xposedScope) {
            scopeContent.append(pkg).append("\n");
        }

        // Write scope file via shell
        String cmd = "echo '" + scopeContent.toString() + "' > " + dir + "/scope 2>/dev/null";
        boolean success = shellBool(cmd) || shellBool("su -c '" + cmd + "'");
        if (success) {
            toast(getString(R.string.module_scope_saved));
        } else {
            toast(getString(R.string.module_scope_save_failed));
        }
    }

    // --- Open module settings ---

    private void openModuleSettings(ModuleInfo m) {
        if (m.settingsActivity == null || m.packageName == null) {
            toast(getString(R.string.module_settings_no_activity));
            return;
        }
        try {
            Intent intent = new Intent();
            intent.setClassName(m.packageName, m.settingsActivity);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            toast(getString(R.string.module_settings_no_activity));
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
