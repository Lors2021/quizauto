package com.lors.quizauto.overlay;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.lors.quizauto.QuizAccessibilityService;

public class OverlayService extends Service {

    public static final String ACTION_START = "com.lors.quizauto.OVERLAY_START";
    public static final String ACTION_STOP  = "com.lors.quizauto.OVERLAY_STOP";
    public static volatile boolean sRunning = false;

    private WindowManager wm;
    private View bubble;
    private WindowManager.LayoutParams params;
    private boolean active = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (bubble == null) showBubble();
        sRunning = true;
        return START_STICKY;
    }

    private void showBubble() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        int size = dp(64);
        FrameLayout root = new FrameLayout(this);
        bubble = root;

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFF44336, 0xFFD32F2F});
        bg.setShape(GradientDrawable.OVAL);
        bg.setStroke(dp(3), Color.WHITE);
        root.setBackground(bg);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(16);
        params.y = dp(120);

        root.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float touchX, touchY;
            boolean moved;
            final int slop = dp(6);

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = params.x; startY = params.y;
                        touchX = e.getRawX(); touchY = e.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - touchX);
                        int dy = (int) (e.getRawY() - touchY);
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved = true;
                        params.x = startX + dx;
                        params.y = startY + dy;
                        wm.updateViewLayout(bubble, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) toggle();
                        return true;
                }
                return false;
            }
        });

        wm.addView(root, params);
    }

    private void toggle() {
        active = !active;
        QuizAccessibilityService.setRunning(active);

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                active
                        ? new int[]{0xFF66BB6A, 0xFF4CAF50}
                        : new int[]{0xFFF44336, 0xFFD32F2F});
        bg.setShape(GradientDrawable.OVAL);
        bg.setStroke(dp(3), Color.WHITE);
        bubble.setBackground(bg);

        Toast.makeText(this, active ? "QuizAuto: СТАРТ" : "QuizAuto: СТОП",
                Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        QuizAccessibilityService.setRunning(false);
        if (bubble != null && wm != null) {
            wm.removeView(bubble);
            bubble = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
