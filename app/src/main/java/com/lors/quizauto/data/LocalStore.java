package com.lors.quizauto.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class LocalStore {
    private static final String PREFS = "quizauto_prefs";
    private static final String KEY_LAST_SYNC = "last_sync";
    private static final String KEY_CYCLES_LIMIT = "cycles_limit";
    private static final String FILE_NAME = "storage.json";
    private static final String UNKNOWN_FILE = "unknown.txt";

    private LocalStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void saveRaw(@NonNull Context ctx, @NonNull String json) throws IOException {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    @NonNull
    public static String loadRaw(@NonNull Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (!f.exists()) return "";
        try {
            byte[] data = Files.readAllBytes(f.toPath());
            return new String(data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    @NonNull
    public static List<Question> loadQuestions(@NonNull Context ctx) {
        String raw = loadRaw(ctx);
        if (raw.isEmpty()) return Collections.emptyList();
        return GitHubRepository.parse(raw);
    }

    public static void setLastSync(Context ctx, long ts) {
        prefs(ctx).edit().putLong(KEY_LAST_SYNC, ts).apply();
    }

    public static long getLastSync(Context ctx) {
        return prefs(ctx).getLong(KEY_LAST_SYNC, 0L);
    }

    public static void setCyclesLimit(Context ctx, int n) {
        prefs(ctx).edit().putInt(KEY_CYCLES_LIMIT, Math.max(0, n)).apply();
    }

    public static int getCyclesLimit(Context ctx) {
        return prefs(ctx).getInt(KEY_CYCLES_LIMIT, 5);
    }

    // ─────────────────────────────────────────────────────────────
    // Режим «собирать неизвестные»
    // ─────────────────────────────────────────────────────────────

    /**
     * Добавляет неизвестный вопрос в файл unknown.txt.
     * Не пишет дубликаты (по нормализованному тексту вопроса).
     */
    public static synchronized void appendUnknown(@NonNull Context ctx,
                                                  @NonNull String question,
                                                  @NonNull List<String> variants) {
        try {
            File f = new File(ctx.getFilesDir(), UNKNOWN_FILE);

            // Проверка на дубликат
            String existing = "";
            if (f.exists()) {
                existing = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            }
            String norm = com.lors.quizauto.match.QuestionMatcher.normalize(question);
            String existingNorm = com.lors.quizauto.match.QuestionMatcher.normalize(existing);
            if (!norm.isEmpty() && existingNorm.contains(norm)) {
                return; // уже собирали
            }

            String time = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                    .format(new Date());

            StringBuilder sb = new StringBuilder();
            sb.append("=== ").append(time).append(" ===\n");
            sb.append("ВОПРОС: ").append(question).append("\n");
            sb.append("ВАРИАНТЫ:\n");
            for (String v : variants) {
                sb.append("  - ").append(v).append("\n");
            }
            sb.append("\n");

            try (FileOutputStream fos = new FileOutputStream(f, true)) {
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    @NonNull
    public static String loadUnknown(@NonNull Context ctx) {
        File f = new File(ctx.getFilesDir(), UNKNOWN_FILE);
        if (!f.exists()) return "";
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public static void clearUnknown(@NonNull Context ctx) {
        File f = new File(ctx.getFilesDir(), UNKNOWN_FILE);
        if (f.exists()) f.delete();
    }
}
