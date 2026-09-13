package com.lors.quizauto;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;
import com.lors.quizauto.data.GitHubRepository;
import com.lors.quizauto.data.LocalStore;
import com.lors.quizauto.overlay.OverlayService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private MaterialCardView cardOverlay, cardAccessibility;
    private MaterialTextView overlayStatus, accessStatus, syncInfo;
    private LinearProgressIndicator progress;
    private TextInputEditText inputCycles;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        cardOverlay = findViewById(R.id.cardOverlay);
        cardAccessibility = findViewById(R.id.cardAccessibility);
        overlayStatus = findViewById(R.id.overlayStatus);
        accessStatus = findViewById(R.id.accessStatus);
        syncInfo = findViewById(R.id.syncInfo);
        progress = findViewById(R.id.syncProgress);
        inputCycles = findViewById(R.id.inputCycles);

        MaterialButton btnSync = findViewById(R.id.btnSync);
        MaterialButton btnOverlaySettings = findViewById(R.id.btnOverlaySettings);
        MaterialButton btnAccessSettings = findViewById(R.id.btnAccessSettings);
        MaterialButton btnToggleOverlay = findViewById(R.id.btnToggleOverlay);
        MaterialButton btnUnknown = findViewById(R.id.btnUnknown);
        MaterialButton btnLog = findViewById(R.id.btnLog);

        btnSync.setOnClickListener(v -> syncFromGithub());
        btnOverlaySettings.setOnClickListener(v -> openOverlayPermission());
        btnAccessSettings.setOnClickListener(v -> openAccessibilitySettings());
        btnToggleOverlay.setOnClickListener(v -> toggleOverlay());
        btnUnknown.setOnClickListener(v -> showUnknown());
        btnLog.setOnClickListener(v -> showLog());

        cardOverlay.setOnClickListener(v -> openOverlayPermission());
        cardAccessibility.setOnClickListener(v -> openAccessibilitySettings());

        int saved = LocalStore.getCyclesLimit(this);
        inputCycles.setText(String.valueOf(saved));
        inputCycles.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                try {
                    int n = Integer.parseInt(s.toString().trim());
                    LocalStore.setCyclesLimit(MainActivity.this, n);
                } catch (Exception ignored) {}
            }
        });

        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    private void refreshState() {
        boolean overlay = Settings.canDrawOverlays(this);
        overlayStatus.setText(overlay ? "Разрешено" : "Не разрешено");

        boolean access = isAccessibilityEnabled();
        accessStatus.setText(access ? "Включено" : "Отключено");

        long ts = LocalStore.getLastSync(this);
        syncInfo.setText(ts == 0
                ? "База ещё не синхронизирована"
                : "Последняя синхронизация: " +
                    new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                            .format(new Date(ts)));
    }

    private boolean isAccessibilityEnabled() {
        android.view.accessibility.AccessibilityManager am =
                (android.view.accessibility.AccessibilityManager)
                        getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<android.accessibilityservice.AccessibilityServiceInfo> list =
                am.getEnabledAccessibilityServiceList(
                        android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (android.accessibilityservice.AccessibilityServiceInfo info : list) {
            if (info.getResolveInfo() != null
                    && info.getResolveInfo().serviceInfo != null
                    && getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) {
                return true;
            }
        }
        return false;
    }

    private void openOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } else {
            Toast.makeText(this, "Разрешение уже выдано", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAccessibilitySettings() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Включение Accessibility")
                .setMessage("Найдите «QuizAuto» в списке служб и включите её вручную. " +
                        "Android не разрешает включать службу программно.")
                .setPositiveButton("Открыть настройки", (d, w) ->
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlayPermission();
            return;
        }

        try {
            int n = Integer.parseInt(inputCycles.getText().toString().trim());
            LocalStore.setCyclesLimit(this, n);
        } catch (Exception e) {
            LocalStore.setCyclesLimit(this, 0);
        }

        Intent i = new Intent(this, OverlayService.class);
        if (OverlayService.sRunning) {
            i.setAction(OverlayService.ACTION_STOP);
        } else {
            i.setAction(OverlayService.ACTION_START);
        }
        startService(i);
    }

    private void syncFromGithub() {
        progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                int n = new GitHubRepository(this).sync().size();
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this, "Синхронизировано: " + n + " вопросов",
                            Toast.LENGTH_LONG).show();
                    refreshState();
                    if (QuizAccessibilityService.sInstance != null) {
                        QuizAccessibilityService.sInstance.reloadQuestions();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this, "Ошибка: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // Неизвестные вопросы
    private void showUnknown() {
        String data = LocalStore.loadUnknown(this);
        if (data.isEmpty()) {
            Toast.makeText(this, "Пока ничего не собрано", Toast.LENGTH_SHORT).show();
            return;
        }

        ScrollView scroll = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(data);
        tv.setTextSize(12);
        tv.setPadding(32, 32, 32, 32);
        tv.setTextIsSelectable(true);
        scroll.addView(tv);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Неизвестные вопросы (" + countRecords(data) + ")")
                .setView(scroll)
                .setPositiveButton("Копировать всё", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("unknown", data));
                    Toast.makeText(this, "Скопировано в буфер обмена",
                            Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Очистить", (d, w) -> {
                    LocalStore.clearUnknown(this);
                    Toast.makeText(this, "Очищено", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    // Логи
    private void showLog() {
        String data = LocalStore.loadLog(this);
        if (data.isEmpty()) {
            Toast.makeText(this, "Логи пусты", Toast.LENGTH_SHORT).show();
            return;
        }

        ScrollView scroll = new ScrollView(this);
        TextView tv = new TextView(this);
        // Показываем последние 5000 символов, чтобы не тормозило
        String preview = data.length() > 5000
                ? "…(начало опущено)…\n\n" + data.substring(data.length() - 5000)
                : data;
        tv.setText(preview);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(32, 32, 32, 32);
        tv.setTextIsSelectable(true);
        scroll.addView(tv);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Логи сервиса (" + data.length() + " симв.)")
                .setView(scroll)
                .setPositiveButton("Копировать всё", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("log", data));
                    Toast.makeText(this, "Все логи скопированы",
                            Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Очистить", (d, w) -> {
                    LocalStore.clearLog(this);
                    Toast.makeText(this, "Логи очищены", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private int countRecords(String data) {
        int count = 0;
        int idx = 0;
        while ((idx = data.indexOf("\"question\"", idx)) != -1) {
            count++;
            idx += 10;
        }
        return count;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}
