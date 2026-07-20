package com.takattowo.bootloaderspoofer;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import io.github.libxposed.service.XposedService;

public class EditKeyboxActivity extends AppCompatActivity implements App.ServiceStateListener {

    private EditText editor;
    private MaterialButton save;
    private volatile XposedService service;
    private XposedService loadedService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_keybox);

        editor = findViewById(R.id.keybox_edit);
        ImageButton back = findViewById(R.id.btn_back);
        save = findViewById(R.id.btn_save);

        back.setOnClickListener(v -> finish());
        save.setOnClickListener(v -> save());
        save.setEnabled(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        App.addServiceStateListener(this, true);
    }

    @Override
    protected void onStop() {
        App.removeServiceStateListener(this);
        super.onStop();
    }

    @Override
    public void onServiceStateChanged(XposedService svc) {
        service = svc;
        runOnUiThread(() -> {
            save.setEnabled(false);
            if (svc == null) {
                loadedService = null;
            } else if (loadedService == svc) {
                save.setEnabled(true);
            } else {
                loadExisting(svc);
            }
        });
    }

    private void loadExisting(XposedService svc) {
        try {
            String xml = ServiceFiles.readString(svc, Config.KEYBOX_FILE,
                    KeyboxLoader.MAX_XML_BYTES);
            editor.setText(xml == null ? "" : xml);
            loadedService = svc;
            save.setEnabled(service == svc);
        } catch (Throwable t) {
            toast("Load failed: " + t.getMessage());
        }
    }

    private void save() {
        XposedService svc = service;
        if (svc == null || loadedService != svc) {
            toast(getString(R.string.toast_not_connected));
            return;
        }

        String xml = editor.getText().toString();
        if (TextUtils.isEmpty(xml.trim())) { toast("Empty input"); return; }

        try {
            KeyboxLoader.validateUserXml(xml);
            ServiceFiles.replaceString(svc, Config.KEYBOX_FILE, xml,
                    KeyboxLoader.MAX_XML_BYTES);
            toast(getString(R.string.toast_restart_required));
            finish();
        } catch (Throwable t) {
            toast("Save failed: " + t.getMessage());
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
