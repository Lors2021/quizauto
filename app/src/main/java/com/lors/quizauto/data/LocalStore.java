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
    // Неизвестные вопросы — пишем в JSON-формате
    // ─────────────────────────────────────────────────────────────

    public static synchronized void appendUnknown(@NonNull Context ctx,
                                                  @NonNull String question,
                                                  @NonNull List<String> variants) {
        try {
            File f = new File(ctx.getFilesDir(), UNKNOWN_FILE);

            // Проверка на дубликат по нормализованному вопросу
            String existing = "";
            if (f.exists()) {
                existing = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            }
            String norm = com.lors.quizauto.match.QuestionMatcher.normalize(question);
            if (!norm.isEmpty()) {
                String existingNorm = com.lors.quizauto.match.QuestionMatcher
                        .normalize(existing);
                if (existingNorm.contains(norm)) {
                    return; // уже записан
                }
            }

            String time = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                    .format(new Date());

            // Экранируем кавычки и переносы строк
            String qEsc = escape(question);
            StringBuilder variantsJson = new StringBuilder();
            for (int i = 0; i < variants.size(); i++) {
                if (i > 0) variantsJson.append(", ");
                variantsJson.append("\"").append(escape(variants.get(i))).append("\"");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("// ").append(time).append("\n");
            sb.append("// Варианты на экране: [")
                    .append(variantsJson).append("]\n");
            sb.append("{ \"question\": \"").append(qEsc)
                    .append("\", \"answer\": \"\" },\n\n");

            try (FileOutputStream fos = new FileOutputStream(f, true)) {
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ")
                .trim();
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
