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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        
