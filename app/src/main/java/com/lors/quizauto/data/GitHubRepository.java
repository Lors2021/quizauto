package com.lors.quizauto.data;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class GitHubRepository {

    private static final String TAG = "GitHubRepo";

    // ⚠️ ЗАМЕНИ НА СВОЮ ССЫЛКУ ⚠️
    // Пример: https://raw.githubusercontent.com/username/quizauto/main/questions.json
    public static final String RAW_URL =
            "https://raw.githubusercontent.com/Lors2021/quizauto/refs/heads/main/questions.json";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build();

    private final Context app;

    public GitHubRepository(@NonNull Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    @WorkerThread
    @NonNull
    public List<Question> sync() throws IOException {
        Request req = new Request.Builder()
                .url(RAW_URL)
                .header("Accept", "application/json")
                .build();

        try (Response resp = CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code());
            }
            ResponseBody body = resp.body();
            if (body == null) throw new IOException("Empty body");
            String json = body.string();
            List<Question> list = parse(json);
            if (list.isEmpty()) {
                throw new IOException("Parsed empty question list");
            }
            LocalStore.saveRaw(app, json);
            LocalStore.setLastSync(app, System.currentTimeMillis());
            Log.i(TAG, "Synced " + list.size() + " questions");
            return list;
        }
    }

    @NonNull
    public static List<Question> parse(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        try {
            Type t = new TypeToken<List<Question>>() {}.getType();
            List<Question> list = new Gson().fromJson(json, t);
            return list == null ? Collections.emptyList() : list;
        } catch (Exception e) {
            Log.e(TAG, "parse error", e);
            return Collections.emptyList();
        }
    }
}
