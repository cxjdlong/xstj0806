package com.musicadd.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

/**
 * 无障碍覆盖层：在锁屏上铺一层全屏歌词（通知卡片只能占 1/3 屏，这个能全屏）。
 *
 * - 窗口类型用 TYPE_ACCESSIBILITY_OVERLAY：这是少数「锁屏上也能显示」的悬浮层
 * - 不拦截触摸（NOT_TOUCHABLE），锁屏操作照常
 * - 背板 80% 透明（#33），锁屏时钟/通知仍看得见
 * - 没开无障碍服务时这个类不会运行，播放走通知卡片（保底）
 */
public class LyricAccessibilityService extends AccessibilityService {

    private static final int ROWS = 8;
    private static LyricAccessibilityService instance;

    private WindowManager wm;
    private View root;
    private final TextView[] rows = new TextView[ROWS];
    private TextView titleView;
    private TextView artistView;
    private int color = Color.WHITE;

    public static boolean isRunning() {
        return instance != null;
    }

    /** 前台服务收到歌词后调这里（没开无障碍时静默忽略） */
    public static void update(String lines, String title, String artist, int color) {
        LyricAccessibilityService s = instance;
        if (s != null) s.apply(lines, title, artist, color);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        buildOverlay();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不需要监听任何事件，只为拿到覆盖层能力
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        destroyOverlay();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        destroyOverlay();
        instance = null;
        super.onDestroy();
    }

    private void buildOverlay() {
        if (root != null) return;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;

        root = LayoutInflater.from(this).inflate(R.layout.lyric_overlay, null);
        rows[0] = root.findViewById(R.id.o_l0);
        rows[1] = root.findViewById(R.id.o_l1);
        rows[2] = root.findViewById(R.id.o_l2);
        rows[3] = root.findViewById(R.id.o_l3);
        rows[4] = root.findViewById(R.id.o_l4);
        rows[5] = root.findViewById(R.id.o_l5);
        rows[6] = root.findViewById(R.id.o_l6);
        rows[7] = root.findViewById(R.id.o_l7);
        titleView = root.findViewById(R.id.o_title);
        artistView = root.findViewById(R.id.o_artist);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        try {
            wm.addView(root, lp);
        } catch (Exception e) {
            root = null;
        }
    }

    private void destroyOverlay() {
        if (root != null && wm != null) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
            }
        }
        root = null;
    }

    private void apply(String lines, String title, String artist, int c) {
        if (root == null) buildOverlay();
        if (root == null) return;
        color = c;
        if (titleView != null) {
            titleView.setText(title == null || title.trim().isEmpty() ? getString(R.string.app_name) : title);
        }
        if (artistView != null) {
            artistView.setText(artist == null ? "" : artist);
        }
        String[] parts = (lines == null ? "" : lines).split("\n", -1);
        boolean empty = true;
        for (int i = 0; i < ROWS; i++) {
            String t = (i < parts.length) ? parts[i] : "";
            if (t != null && !t.trim().isEmpty()) empty = false;
            if (rows[i] != null) rows[i].setText(t);
        }
        if (rows[3] != null) {
            if (empty) rows[3].setText("♪");
            rows[3].setTextColor(color);
        }
    }
}
