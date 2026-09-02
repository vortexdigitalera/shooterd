package com.takattowo.bootloaderspoofer;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mini Proot activity — manage the built-in root provider.
 *
 * Shows daemon status, start/stop controls, root test, and an app
 * whitelist for controlling which apps can use the su binary.
 */
public class MiniProotActivity extends Activity {

    private static final String TAG = "MiniProotActivity";

    private TextView daemonStatus;
    private TextView testResult;
    private TextView installHint;
    private MaterialSwitch switchAllowAll;
    private RecyclerView appList;
    private AppAdapter adapter;
    private final List<MiniProotManager.AppInfo> allApps = new ArrayList<>();
    private final List<MiniProotManager.AppInfo> filteredApps = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_miniproot);

        // Back button
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        daemonStatus = findViewById(R.id.daemon_status);
        testResult = findViewById(R.id.test_result);
        installHint = findViewById(R.id.install_hint);
        switchAllowAll = findViewById(R.id.switch_allow_all);
        appList = findViewById(R.id.app_list);
        TextView emptyState = findViewById(R.id.empty_state);

        // Set up RecyclerView
        adapter = new AppAdapter();
        appList.setLayoutManager(new LinearLayoutManager(this));
        appList.setAdapter(adapter);

        // Search
        com.google.android.material.textfield.TextInputEditText searchInput =
                findViewById(R.id.search_input);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                filterApps(s.toString());
            }
        });

        // Start button
        findViewById(R.id.btn_start).setOnClickListener(v -> {
            executor.execute(() -> {
                boolean ok = MiniProotManager.startDaemon();
                mainHandler.post(() -> {
                    refreshStatus();
                    showTestResult(ok ? "Daemon started" : "Failed to start daemon");
                });
            });
        });

        // Stop button
        findViewById(R.id.btn_stop).setOnClickListener(v -> {
            executor.execute(() -> {
                boolean ok = MiniProotManager.stopDaemon();
                mainHandler.post(() -> {
                    refreshStatus();
                    showTestResult(ok ? "Daemon stopped" : "Failed to stop daemon");
                });
            });
        });

        // Test button
        findViewById(R.id.btn_test).setOnClickListener(v -> {
            testResult.setVisibility(View.VISIBLE);
            testResult.setText("Testing...");
            executor.execute(() -> {
                String result = MiniProotManager.testRoot();
                mainHandler.post(() -> showTestResult(result));
            });
        });

        // Allow all switch
        switchAllowAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            executor.execute(() -> {
                if (isChecked) {
                    MiniProotManager.setAllowAll();
                } else {
                    // Reset to empty whitelist
                    MiniProotManager.setWhitelist(new ArrayList<>());
                }
                mainHandler.post(this::loadApps);
            });
        });

        // View log button
        findViewById(R.id.btn_view_log).setOnClickListener(v -> {
            executor.execute(() -> {
                String log = MiniProotManager.getDaemonLog();
                mainHandler.post(() -> showTestResult(log));
            });
        });

        // Initial load
        refreshStatus();
        loadApps();
    }

    private void refreshStatus() {
        executor.execute(() -> {
            boolean running = MiniProotManager.isDaemonRunning();
            boolean installed = MiniProotManager.isInstalled();
            boolean allowAll = MiniProotManager.isAllowAll();

            mainHandler.post(() -> {
                if (!installed) {
                    daemonStatus.setText(R.string.miniproot_not_installed);
                    installHint.setVisibility(View.VISIBLE);
                } else if (running) {
                    daemonStatus.setText(R.string.miniproot_running);
                    installHint.setVisibility(View.GONE);
                } else {
                    daemonStatus.setText(R.string.miniproot_stopped);
                    installHint.setVisibility(View.GONE);
                }

                // Only update switch if state actually changed to avoid redundant listener churn
                if (switchAllowAll.isChecked() != allowAll) {
                    switchAllowAll.setOnCheckedChangeListener(null);
                    switchAllowAll.setChecked(allowAll);
                    switchAllowAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        executor.execute(() -> {
                            if (isChecked) {
                                MiniProotManager.setAllowAll();
                            } else {
                                MiniProotManager.setWhitelist(new ArrayList<>());
                            }
                            mainHandler.post(this::loadApps);
                        });
                    });
                }
            });
        });
    }

    private void loadApps() {
        executor.execute(() -> {
            List<MiniProotManager.AppInfo> apps = MiniProotManager.getInstalledApps(this);
            synchronized (allApps) {
                allApps.clear();
                allApps.addAll(apps);
            }
            mainHandler.post(() -> {
                filterApps(""); // Show all initially
            });
        });
    }

    private void filterApps(String query) {
        synchronized (allApps) {
            filteredApps.clear();
            if (query == null || query.isEmpty()) {
                filteredApps.addAll(allApps);
            } else {
                String q = query.toLowerCase(Locale.getDefault());
                for (MiniProotManager.AppInfo app : allApps) {
                    if (app.name.toLowerCase(Locale.getDefault()).contains(q)
                            || app.packageName.toLowerCase(Locale.getDefault()).contains(q)) {
                        filteredApps.add(app);
                    }
                }
            }
        }
        adapter.notifyDataSetChanged();

        TextView emptyState = findViewById(R.id.empty_state);
        emptyState.setVisibility(filteredApps.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showTestResult(String text) {
        testResult.setVisibility(View.VISIBLE);
        testResult.setText(text);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    // --- RecyclerView Adapter ---

    private class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.row_proot_app, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            MiniProotManager.AppInfo app = filteredApps.get(position);
            holder.appName.setText(app.name);
            holder.appPackage.setText(String.format(Locale.getDefault(),
                    "%s (uid: %d)", app.packageName, app.uid));

            holder.appSwitch.setOnCheckedChangeListener(null);
            holder.appSwitch.setChecked(app.allowed);
            holder.appSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                executor.execute(() -> {
                    if (isChecked) {
                        MiniProotManager.addUid(app.uid);
                    } else {
                        MiniProotManager.removeUid(app.uid);
                    }
                });
            });
        }

        @Override
        public int getItemCount() {
            return filteredApps.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final TextView appName;
            final TextView appPackage;
            final MaterialSwitch appSwitch;

            ViewHolder(View v) {
                super(v);
                appName = v.findViewById(R.id.app_name);
                appPackage = v.findViewById(R.id.app_package);
                appSwitch = v.findViewById(R.id.app_switch);
            }
        }
    }
}
