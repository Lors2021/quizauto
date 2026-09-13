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
    private static final long ACTION_DELAY_MS = 400L;
    private static final long NEXT_SCAN_DELAY_MS = 150L;
    private static final double QUESTION_THRESHOLD = 0.80;
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
            "ваша награда"
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

    // Чтобы не записывать один и тот же неизвестный вопрос много раз подряд
    private String lastUnknownSaved = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.i(TAG, "AccessibilityService connected");
        reloadQuestions();
        cyclesLimit = LocalStore.getCyclesLimit(this);
        cyclesDone = 0;
        acquireWakeLock();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        sRunning = false;
        handler.removeCallbacksAndMessages(null);
        releaseWakeLock();
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
        Log.i(TAG, "Loaded questions: " + questions.size());
    }

    public void resetCycles() {
        cyclesDone = 0;
        cyclesLimit = LocalStore.getCyclesLimit(this);
        Log.i(TAG, "Cycles reset. Limit: " + cyclesLimit);
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
        } catch (Exception e) {
            Log.w(TAG, "WakeLock failed: " + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
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

            // ─── P1: победа ───
            AccessibilityNodeInfo restartBtn = findFirstByText(nodes, 0.85,
                    "Новая игра", "Заново", "Играть заново");
            if (restartBtn != null) {
                cyclesDone++;
                Log.i(TAG, "P1: cycle done. Total: " + cyclesDone
                        + (cyclesLimit > 0 ? "/" + cyclesLimit : " (∞)"));

                if (cyclesLimit > 0 && cyclesDone >= cyclesLimit) {
                    Log.i(TAG, "Cycle limit reached, stopping");
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

                if (smartClick(restartBtn)) {
                    Log.i(TAG, "P1: clicked Новая игра");
                }
                return;
            }

            // ─── P2: экран результата ───
            boolean onResultScreen =
                    findFirstByText(nodes, 0.85, "Далее") != null
                            || findFirstByText(nodes, 0.85, "Верно") != null
                            || findFirstByText(nodes, 0.85, "Неправильно") != null;

            if (onResultScreen) {
                if (tryClickFirstByText(nodes, 0.85, "Далее")) {
                    Log.i(TAG, "P2: click Далее");
                    handler.postDelayed(() -> {
                        isProcessing = false;
                        handler.post(scanRunnable);
                    }, NEXT_SCAN_DELAY_MS);
                    return;
                }
                return;
            }

            // ─── P3: экран вопроса ───
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
            // 🆕 Сохраняем неизвестный вопрос
            saveUnknownQuestion(nodes, visibleAnswers);
            Log.d(TAG, "No question match — saved to unknown");
            return;
        }
        Log.i(TAG, "Matched Q: " + matched.getQuestion());

        AccessibilityNodeInfo answerNode = findAnswerNode(nodes, matched.getAnswer());
        if (answerNode == null) {
            Log.w(TAG, "Answer node not found: " + matched.getAnswer());
            return;
        }

        if (!smartClick(answerNode)) {
            Log.w(TAG, "Failed to click answer");
            return;
        }
        Log.i(TAG, "Clicked answer: " + matched.getAnswer());

        handler.postDelayed(() -> {
            AccessibilityNodeInfo root2 = getRootInActiveWindow();
            if (root2 == null) return;
            List<AccessibilityNodeInfo> nodes2 = new ArrayList<>();
            collectNodes(root2, nodes2);
            AccessibilityNodeInfo checkBtn = findFirstByText(nodes2, 0.85, "Проверить");
            if (checkBtn != null && checkBtn.isEnabled()) {
                smartClick(checkBtn);
                Log.i(TAG, "Clicked Проверить");
            } else {
                Log.w(TAG, "Проверить not found or disabled");
            }
        }, ACTION_DELAY_MS);
    }

    /**
     * 🆕 Сохраняем неизвестный вопрос и его варианты.
     * Ищем самый длинный текстовый узел (это и будет вопрос).
     */
    private void saveUnknownQuestion(@NonNull List<AccessibilityNodeInfo> nodes,
                                     @NonNull List<String> visibleAnswers) {
        if (visibleAnswers.size() < 2) return; // не похоже на экран вопроса

        // Ищем вопрос — самый длинный текст, не входящий в UI_NOISE и не вариант ответа
        String questionCandidate = null;
        for (AccessibilityNodeInfo n : nodes) {
            CharSequence cs = n.getText();
            if (cs == null) continue;
            String s = cs.toString().trim();
            if (s.length() < 10 || s.length() > 300) continue;
            String norm = QuestionMatcher.normalize(s);
            if (UI_NOISE.contains(norm)) continue;
            if (s.matches("\\d+")) continue;
            if (visibleAnswers.contains(s)) continue; // это вариант ответа, не вопрос

            if (questionCandidate == null || s.length() > questionCandidate.length()) {
                questionCandidate = s;
            }
        }

        if (questionCandidate == null) return;

        // Защита от повторной записи
        String normQ = QuestionMatcher.normalize(questionCandidate);
        if (normQ.equals(lastUnknownSaved)) return;
        lastUnknownSaved = normQ;

        LocalStore.appendUnknown(this, questionCandidate, visibleAnswers);
        Log.i(TAG, "Saved unknown Q: " + questionCandidate
                + " | variants: " + visibleAnswers.size());
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
            if (candidate.length() < 10 || candidate.length() > 300) continue;
            String norm = QuestionMatcher.normalize(candidate);
            if (UI_NOISE.contains(norm)) continue;
            if (candidate.matches("\\d+")) continue;

            for (Question q : questions) {
                double s = QuestionMatcher.similarity(candidate, q.getQuestion());
                if (s < QUESTION_THRESHOLD) continue;

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

    private boolean tryClickFirstByText(@NonNull List<AccessibilityNodeInfo> nodes,
                                        double threshold, String... texts) {
        AccessibilityNodeInfo node = findFirstByText(nodes, threshold, texts);
        return node != null && smartClick(node);
    }

    private boolean smartClick(@NonNull AccessibilityNodeInfo node) {
        CharSequence cs = node.getText();
        if (cs != null && NEVER_CLICK.contains(QuestionMatcher.normalize(cs.toString()))) {
            Log.w(TAG, "Blocked click: " + cs);
            return false;
        }

        AccessibilityNodeInfo cur = node;
        int depth = 0;
        while (cur != null && depth < 6) {
            if (cur.isClickable()) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            }
            cur = cur.getParent();
            depth++;
        }

        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        if (rect.width() <= 0 || rect.height() <= 0) return false;
        return tapAt(rect.exactCenterX(), rect.exactCenterY());
    }

    private boolean tapAt(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0L, 40L);
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
