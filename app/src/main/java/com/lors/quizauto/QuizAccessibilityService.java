package com.lors.quizauto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.lors.quizauto.data.LocalStore;
import com.lors.quizauto.data.Question;
import com.lors.quizauto.match.QuestionMatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class QuizAccessibilityService extends AccessibilityService {

    private static final String TAG = "QuizAutoSvc";

    private static final long DEBOUNCE_MS = 150L;
    private static final long AFTER_ANSWER_CLICK_MS = 600L;
    private static final long AFTER_CHECK_RETRY_MS = 300L;
    private static final long NEXT_SCAN_DELAY_MS = 200L;
    private static final long P2_COOLDOWN_MS = 800L;

    // ⏱ Задержка перед первым кликом — даём викторине отрисовать варианты
    private static final long FIRST_SEEN_TO_CLICK_MS = 450L;
    // Через сколько считать вопрос "новым"
    private static final long QUESTION_FRESH_MS = 3500L;
    // Блокировка повтора ответа на тот же вопрос
    private static final long ANSWER_COOLDOWN_MS = 3000L;

    // Автоперезагрузка базы
    private static final long BASE_RELOAD_INTERVAL_MS = 5000L;

    private static final double QUESTION_THRESHOLD = 0.80;
    private static final double SHORT_QUESTION_THRESHOLD = 0.68;
    private static final int SHORT_QUESTION_LEN = 25;
    private static final double ANSWER_FUZZY = 0.88;

    private static final Set<String> UI_NOISE = new HashSet<>(Arrays.asList(
            "выберите правильный ответ",
            "проверить",
            "далее",
            "верно",
            "неправильно",
            "новая игра",
            "закрыть",
            "победа",
            "ваша награда",
            "загрузка завершена",
            "загрузка",
            "пополните энергию чтобы сыграть",
            "пополните энергию",
            "нет энергии",
            "нет попыток"
    ));

    private static final Set<String> NEVER_CLICK = new HashSet<>(Arrays.asList(
            "закрыть", "выход", "отмена", "назад", "close", "exit", "cancel"
    ));

    public static volatile boolean sRunning = false;
    public static volatile QuizAccessibilityService sInstance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scanRunnable = this::runStateMachine;
    private boolean isProcessing = false;
    private List<Question> questions = new ArrayList<>();

    private int cyclesDone = 0;
    private int cyclesLimit = 5;

    private PowerManager.WakeLock wakeLock;

    private String lastSavedQuestionId = "";
    private long lastSavedQuestionTime = 0L;

    private String lastAnsweredQuestion = "";
    private long lastAnsweredTime = 0L;

    // 🆕 Отслеживание "свежего" вопроса — чтобы не кликать сразу
    private String currentQuestionId = "";
    private long currentQuestionFirstSeen = 0L;

    private long lastNextClickTime = 0L;
    private long lastBaseReloadTime = 0L;

    private final Runnable baseReloader = new Runnable() {
        @Override
        public void run() {
            if (!sRunning) return;
            if (questions.isEmpty()) {
                reloadQuestions();
            }
            handler.postDelayed(this, BASE_RELOAD_INTERVAL_MS);
        }
    };

    private void log(String msg) {
        Log.i(TAG, msg);
        LocalStore.appendLog(this, msg);
    }

    private void logW(String msg) {
        Log.w(TAG, msg);
        LocalStore.appendLog(this, "WARN: " + msg);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        LocalStore.appendLog(this, "\n\n========== SERVICE CONNECTED ==========");
        log("Service connected");
        reloadQuestions();
        cyclesLimit = LocalStore.getCyclesLimit(this);
        cyclesDone = 0;
        acquireWakeLock();
        log("Cycles limit: " + cyclesLimit);

        // Автоперезагрузка базы, если она пустая
        handler.removeCallbacks(baseReloader);
        handler.postDelayed(baseReloader, BASE_RELOAD_INTERVAL_MS);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        sRunning = false;
        handler.removeCallbacksAndMessages(null);
        handler.removeCallbacks(baseReloader);
        releaseWakeLock();
        log("Service disconnected");
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!sRunning || event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            return;
        }
        handler.removeCallbacks(scanRunnable);
        handler.postDelayed(scanRunnable, DEBOUNCE_MS);
    }

    @Override
    public void onInterrupt() { }

    public void reloadQuestions() {
        this.questions = LocalStore.loadQuestions(this);
        log("Loaded questions: " + questions.size());
        if (questions.isEmpty()) logW("Question base is EMPTY. Sync first.");
    }

    public void resetCycles() {
        cyclesDone = 0;
        cyclesLimit = LocalStore.getCyclesLimit(this);
        lastSavedQuestionId = "";
        lastAnsweredQuestion = "";
        currentQuestionId = "";
        lastNextClickTime = 0L;
        log("Cycles reset. Limit: " + cyclesLimit);
        handler.removeCallbacks(baseReloader);
        handler.postDelayed(baseReloader, BASE_RELOAD_INTERVAL_MS);
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ON_AFTER_RELEASE,
                    "QuizAuto::screenWake");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(4 * 60 * 60 * 1000L);
            log("WakeLock acquired");
        } catch (Exception e) {
            logW("WakeLock failed: " + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                log("WakeLock released");
            }
        } catch (Exception ignored) {}
        wakeLock = null;
    }

    private void runStateMachine() {
        if (!sRunning || isProcessing) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        isProcessing = true;
        try {
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            collectNodes(root, nodes);

            // P1: победа
            AccessibilityNodeInfo restartBtn = findFirstByText(nodes, 0.85,
                    "Новая игра", "Заново", "Играть заново");
            if (restartBtn != null) {
                cyclesDone++;
                log("P1: cycle done. Total: " + cyclesDone
                        + (cyclesLimit > 0 ? "/" + cyclesLimit : " (inf)"));

                lastSavedQuestionId = "";
                lastAnsweredQuestion = "";
                currentQuestionId = "";

                if (cyclesLimit > 0 && cyclesDone >= cyclesLimit) {
                    log("Cycle limit reached, stopping");
                    final int done = cyclesDone;
                    handler.post(() -> {
                        sRunning = false;
                        cyclesDone = 0;
                        releaseWakeLock();
                        Toast.makeText(
                                QuizAccessibilityService.this,
                                "QuizAuto: пройдено " + done + " циклов, остановлено",
                                Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                if (smartClick(restartBtn)) log("P1: clicked Новая игра");
                return;
            }

            // P2: экран результата — только "Далее"
            AccessibilityNodeInfo nextBtn = findFirstByText(nodes, 0.88, "Далее");
            if (nextBtn != null) {
                long now = System.currentTimeMillis();
                if (now - lastNextClickTime > P2_COOLDOWN_MS) {
                    lastNextClickTime = now;
                    if (smartClick(nextBtn)) {
                        log("P2: clicked Далее");
                        // Сбрасываем "текущий вопрос" — новый будет другим
                        currentQuestionId = "";
                        handler.postDelayed(() -> {
                            isProcessing = false;
                            handler.post(scanRunnable);
                        }, NEXT_SCAN_DELAY_MS);
                    }
                }
                return;
            }

            // P3: экран вопроса
            handleQuestionScreen(nodes);

        } finally {
            handler.postDelayed(() -> isProcessing = false, 100L);
        }
    }

    private void handleQuestionScreen(@NonNull List<AccessibilityNodeInfo> nodes) {
        if (questions.isEmpty()) {
            reloadQuestions();
            if (questions.isEmpty()) return;
        }

        List<String> visibleAnswers = collectVisibleAnswers(nodes);
        Question matched = findBestQuestionMatch(nodes, visibleAnswers);

        if (matched == null) {
            saveUnknownQuestion(nodes);
            return;
        }

        String qId = QuestionMatcher.normalize(matched.getQuestion());
        long now = System.currentTimeMillis();

        // 🆕 Отслеживаем "свежий вопрос" — не кликаем сразу после появления
        if (!qId.equals(currentQuestionId)) {
            // Первый раз видим этот вопрос
            currentQuestionId = qId;
            currentQuestionFirstSeen = now;
            log("Fresh Q detected: " + matched.getQuestion());
            // Не кликаем — подождём следующего скана
            handler.postDelayed(() -> {
                isProcessing = false;
                handler.post(scanRunnable);
            }, FIRST_SEEN_TO_CLICK_MS);
            return;
        }

        // Если вопрос "свежий" — ждём, пока пройдёт FIRST_SEEN_TO_CLICK_MS
        if (now - currentQuestionFirstSeen < FIRST_SEEN_TO_CLICK_MS) {
            log("Q still fresh, waiting " + (FIRST_SEEN_TO_CLICK_MS - (now - currentQuestionFirstSeen)) + "ms");
            handler.postDelayed(() -> {
                isProcessing = false;
                handler.post(scanRunnable);
            }, FIRST_SEEN_TO_CLICK_MS - (now - currentQuestionFirstSeen) + 50L);
            return;
        }

        // Анти-дубликат: если мы уже отвечали на этот вопрос в последние 3 сек — пропускаем
        if (qId.equals(lastAnsweredQuestion) && (now - lastAnsweredTime) < ANSWER_COOLDOWN_MS) {
            return;
        }

        log("Matched Q: " + matched.getQuestion());

        AccessibilityNodeInfo answerNode = findAnswerNode(nodes, matched.getAnswer());
        if (answerNode == null) {
            logW("Answer node not found: " + matched.getAnswer()
                    + " | visible: " + visibleAnswers);
            return;
        }

        if (!smartClick(answerNode)) {
            logW("Failed to click answer");
            return;
        }

        lastAnsweredQuestion = qId;
        lastAnsweredTime = now;
        log("Clicked answer: " + matched.getAnswer());

        clickCheckWithRetry(0);
    }

    private void clickCheckWithRetry(int attempt) {
        if (attempt > 3) {
            logW("Проверить: gave up after 4 attempts");
            return;
        }
        handler.postDelayed(() -> {
            AccessibilityNodeInfo root2 = getRootInActiveWindow();
            if (root2 == null) return;
            List<AccessibilityNodeInfo> nodes2 = new ArrayList<>();
            collectNodes(root2, nodes2);
            AccessibilityNodeInfo checkBtn = findFirstByText(nodes2, 0.85, "Проверить");
            if (checkBtn == null) {
                logW("Проверить: not found (attempt " + attempt + ")");
                clickCheckWithRetry(attempt + 1);
                return;
            }
            if (!checkBtn.isEnabled()) {
                log("Проверить: disabled (attempt " + attempt + "), retrying");
                clickCheckWithRetry(attempt + 1);
                return;
            }
            if (smartClick(checkBtn)) {
                log("Clicked Проверить (attempt " + attempt + ")");
            } else {
                logW("Проверить: smartClick failed (attempt " + attempt + ")");
                clickCheckWithRetry(attempt + 1);
            }
        }, attempt == 0 ? AFTER_ANSWER_CLICK_MS : AFTER_CHECK_RETRY_MS);
    }

    private void saveUnknownQuestion(@NonNull List<AccessibilityNodeInfo> nodes) {
        List<String> allTexts = new ArrayList<>();
        for (AccessibilityNodeInfo n : nodes) {
            CharSequence cs = n.getText();
            if (cs == null) cs = n.getContentDescription();
            if (cs == null) continue;
            String s = cs.toString().trim();
            if (s.isEmpty()) continue;
            if (s.length() > 400) continue;
            allTexts.add(s);
        }

        if (allTexts.isEmpty()) return;

        // 1) Ищем вопросы со знаком "?"
        String questionCandidate = null;
        for (String s : allTexts) {
            String norm = QuestionMatcher.normalize(s);
            if (UI_NOISE.contains(norm)) continue;
            if (s.matches("^\\d+$")) continue;
            if (s.length() < 8) continue;
            if (norm.contains("загрузка")) continue;
            if (norm.contains("энерг")) continue;
            if (norm.contains("попытк")) continue;
            if (!s.contains("?")) continue;
            if (questionCandidate == null || s.length() > questionCandidate.length()) {
                questionCandidate = s;
            }
        }

        // 2) Если вопроса с "?" нет — не сохраняем вообще.
        //    Служебные строки типа "Выберите правильный ответ" — мусор.
        if (questionCandidate == null) {
            return;
        }

        String qId = QuestionMatcher.normalize(questionCandidate);
        long now = System.currentTimeMillis();
        if (qId.equals(lastSavedQuestionId) && (now - lastSavedQuestionTime) < 5000L) {
            return;
        }
        lastSavedQuestionId = qId;
        lastSavedQuestionTime = now;

        List<String> variants = new ArrayList<>();
        for (String s : allTexts) {
            if (s.equals(questionCandidate)) continue;
            String norm = QuestionMatcher.normalize(s);
            if (UI_NOISE.contains(norm)) continue;
            if (s.matches("^\\d+$")) continue;
            if (s.length() > 100) continue;
            if (norm.contains("загрузка")) continue;
            if (norm.contains("энерг")) continue;
            if (norm.contains("попытк")) continue;
            if (!variants.contains(s)) variants.add(s);
        }

        log("SAVING: Q=\"" + questionCandidate + "\" | variants=" + variants.size());
        LocalStore.appendUnknown(this, questionCandidate, variants);
    }

    @NonNull
    private List<String> collectVisibleAnswers(@NonNull List<AccessibilityNodeInfo> nodes) {
        List<String> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : nodes) {
            CharSequence cs = n.getText();
            if (cs == null) continue;
            String s = cs.toString().trim();
            if (s.length() < 2 || s.length() > 60) continue;
            String norm = QuestionMatcher.normalize(s);
            if (UI_NOISE.contains(norm)) continue;
            if (s.matches("\\d+")) continue;
            out.add(s);
        }
        return out;
    }

    @Nullable
    private Question findBestQuestionMatch(@NonNull List<AccessibilityNodeInfo> nodes,
                                           @NonNull List<String> visibleAnswers) {
        Question best = null;
        double bestScore = QUESTION_THRESHOLD;

        for (AccessibilityNodeInfo n : nodes) {
            CharSequence cs = n.getText();
            if (cs == null) continue;
            String candidate = cs.toString().trim();
            if (candidate.length() < 8 || candidate.length() > 300) continue;
            String norm = QuestionMatcher.normalize(candidate);
            if (UI_NOISE.contains(norm)) continue;
            if (candidate.matches("\\d+")) continue;
            if (norm.contains("загрузка")) continue;
            if (norm.contains("энерг")) continue;

            double threshold = candidate.length() <= SHORT_QUESTION_LEN
                    ? SHORT_QUESTION_THRESHOLD
                    : QUESTION_THRESHOLD;

            for (Question q : questions) {
                double s = QuestionMatcher.similarity(candidate, q.getQuestion());
                if (s < threshold) continue;

                boolean answerVisible = false;
                for (String va : visibleAnswers) {
                    if (QuestionMatcher.similarity(va, q.getAnswer()) >= 0.85) {
                        answerVisible = true;
                        break;
                    }
                }
                if (answerVisible) s += 0.15;

                if (s > bestScore) {
                    bestScore = s;
                    best = q;
                }
            }
        }
        return best;
    }

    @Nullable
    private AccessibilityNodeInfo findAnswerNode(@NonNull List<AccessibilityNodeInfo> nodes,
                                                 @NonNull String correctAnswer) {
        String normAnswer = QuestionMatcher.normalize(correctAnswer);

        for (AccessibilityNodeInfo n : nodes) {
            CharSequence cs = n.getText();
            if (cs == null) continue;
            if (QuestionMatcher.normalize(cs.toString()).equals(normAnswer)) {
                return n;
            }
        }

        AccessibilityNodeInfo best = null;
        double bestScore = ANSWER_FUZZY;
        for (AccessibilityNodeInfo n : nodes) {
            CharSequence cs = n.getText();
            if (cs == null) continue;
            String s = cs.toString().trim();
            if (s.length() < 2 || s.length() > 60) continue;
            if (UI_NOISE.contains(QuestionMatcher.normalize(s))) continue;
            double score = QuestionMatcher.similarity(s, correctAnswer);
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    private void collectNodes(@NonNull AccessibilityNodeInfo node,
                              @NonNull List<AccessibilityNodeInfo> out) {
        out.add(node);
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectNodes(child, out);
        }
    }

    @Nullable
    private AccessibilityNodeInfo findFirstByText(@NonNull List<AccessibilityNodeInfo> nodes,
                                                  double threshold, String... texts) {
        for (String t : texts) {
            AccessibilityNodeInfo best = null;
            double bestScore = threshold;
            for (AccessibilityNodeInfo n : nodes) {
                CharSequence cs = n.getText();
                if (cs == null) cs = n.getContentDescription();
                if (cs == null) continue;
                double s = QuestionMatcher.similarity(cs.toString(), t);
                if (s > bestScore) {
                    bestScore = s;
                    best = n;
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private boolean smartClick(@NonNull AccessibilityNodeInfo node) {
        CharSequence cs = node.getText();
        if (cs != null && NEVER_CLICK.contains(QuestionMatcher.normalize(cs.toString()))) {
            logW("Blocked click: " + cs);
            return false;
        }

        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        if (rect.width() <= 0 || rect.height() <= 0) {
            AccessibilityNodeInfo cur = node.getParent();
            int depth = 0;
            while (cur != null && depth < 6) {
                cur.getBoundsInScreen(rect);
                if (rect.width() > 0 && rect.height() > 0) break;
                cur = cur.getParent();
                depth++;
            }
        }

        if (rect.width() <= 0 || rect.height() <= 0) {
            AccessibilityNodeInfo cur = node;
            int depth = 0;
            while (cur != null && depth < 6) {
                if (cur.isClickable()) {
                    if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true;
                    }
                }
                cur = cur.getParent();
                depth++;
            }
            return false;
        }

        float x = rect.exactCenterX();
        float y = rect.exactCenterY();
        boolean ok = tapAt(x, y);

        if (!ok) {
            AccessibilityNodeInfo cur = node;
            int depth = 0;
            while (cur != null && depth < 6) {
                if (cur.isClickable()) {
                    if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true;
                    }
                }
                cur = cur.getParent();
                depth++;
            }
        }
        return ok;
    }

    private boolean tapAt(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0L, 50L);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        return dispatchGesture(gesture, null, null);
    }

    public static void setRunning(boolean value) {
        sRunning = value;
        if (sInstance != null) {
            if (value) {
                sInstance.resetCycles();
                sInstance.acquireWakeLock();
            } else {
                sInstance.releaseWakeLock();
            }
        }
    }
}
