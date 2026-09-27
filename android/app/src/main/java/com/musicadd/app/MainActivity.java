package com.musicadd.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;

/**
 * NAS歌下载 · 安卓壳（在线 WebView）
 *
 * 直接加载 NAS 上的 music-add 网页（默认 https://music.dx66.top:8888/），页面自己做手机适配。
 * 壳层负责：全屏显示、禁用缩放/回弹、加载失败给改地址入口、返回键交给网页处理（回首页而不是直接退出）、
 * 以及把播放状态交给 LyricService 的原生 MediaSession（耳机线控 / 车机上一首下一首）。
 *
 * 后门：连按返回键 3 次（1.5 秒内）= 打开服务器地址设置页。
 */
public class MainActivity extends Activity {

    private static final String DEFAULT_URL = "https://music.dx66.top:8888/";
    private static final String PREFS = "musicadd";
    private static final String KEY_URL = "server_url";
    private static final String KEY_USER = "fnos_user";
    private static final String SETUP_PAGE = "file:///android_asset/setup.html";

    /** 给 LyricService 回调网页用（耳机键 → 网页播放控制） */
    private static WeakReference<MainActivity> INSTANCE = new WeakReference<>(null);

    private WebView web;
    private SharedPreferences prefs;
    private long lastBackAt = 0L;
    private int backCount = 0;
    private boolean isPlaying = false;   // 播放中就不挂起 WebView（锁屏/后台继续放）
    private AlertDialog exitDialog;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        INSTANCE = new WeakReference<>(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setDefaultTextEncodingName("UTF-8");
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(0xFFF2F4F8);          // 浅色主题：别用黑色背板（否则页面切换时闪黑）
        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;   // 页面内跳转都留在 WebView
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    web.loadUrl(SETUP_PAGE);   // 连不上 → 给改地址的机会
                }
            }
        });

        String saved = prefs.getString(KEY_URL, "");
        String user = prefs.getString(KEY_USER, "");
        if (saved == null || saved.trim().isEmpty()) {
            web.loadUrl(SETUP_PAGE);          // 还没配过地址 → 设置页
        } else if (user == null || user.trim().isEmpty()) {
            web.loadUrl(saved + (saved.contains("?") ? "&" : "?") + "local=1");   // 无账号 → 本地歌单模式
        } else {
            web.loadUrl(buildUrl(saved, user));
        }

        // Android 13+ 需要运行时授予通知权限，否则锁屏歌词通知不显示
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 把歌词/播放状态丢给前台服务（锁屏通知 + 原生 MediaSession）。lines 是多行歌词，用换行符分隔 */
    private void sendToService(String action, String lines, String title, String artist, String colorHex) {
        isPlaying = true;
        Intent i = new Intent(this, LyricService.class);
        i.setAction(action);
        i.putExtra("lines", lines == null ? "" : lines);
        i.putExtra("title", title == null ? "" : title);
        i.putExtra("artist", artist == null ? "" : artist);
        i.putExtra("color", parseColor(colorHex));
        startServiceSafe(i);
    }

    /** 起服务（O 以上走 startForegroundService，失败不崩） */
    private void startServiceSafe(Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) {
        }
    }

    /** "#RRGGBB" / "#AARRGGBB" → int 颜色 */
    private static int parseColor(String hex) {
        try {
            if (hex == null || hex.trim().isEmpty()) return 0xFFFFFFFF;
            String s = hex.trim();
            if (s.startsWith("#")) s = s.substring(1);
            if (s.length() == 8) s = s.substring(2);
            return (int) (0xFF000000L | Long.parseLong(s, 16));
        } catch (Exception e) {
            return 0xFFFFFFFF;
        }
    }

    /** 地址 + 账号 → 带 ?u= 的 URL，网页端会自动登录并跳过登录页 */
    private String buildUrl(String base, String user) {
        String sep = base.contains("?") ? "&" : "?";
        if (user == null || user.trim().isEmpty()) return base;
        return base + sep + "u=" + Uri.encode(user.trim());
    }

    /* ---------------- 原生 MediaSession → 网页（耳机线控 / 车机按键） ---------------- */

    /** LyricService 收到媒体键后调这里，把动作转给网页 */
    static void sendMediaCmd(String cmd) {
        MainActivity a = INSTANCE.get();
        if (a == null || cmd == null) return;
        final String c = cmd.replace("'", "");   // 只可能是固定动作名，做个保险
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (a.web != null) {
                        a.web.evaluateJavascript("window.maNative && window.maNative.cmd('" + c + "')", null);
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** 暴露给内置设置页 / 网页的桥 */
    public class Bridge {
        @JavascriptInterface
        public String defaultUrl() {
            return DEFAULT_URL;
        }

        @JavascriptInterface
        public String savedUrl() {
            String u = prefs.getString(KEY_URL, "");
            return u == null ? "" : u;
        }

        @JavascriptInterface
        public String savedUser() {
            String u = prefs.getString(KEY_USER, "");
            return u == null ? "" : u;
        }

        /** 网页歌词换行 → 更新锁屏通知（lines 用 \n 分隔，中间那行是当前句） */
        @JavascriptInterface
        public void lyric(String lines, String title, String artist, String colorHex) {
            sendToService(LyricService.ACTION_UPDATE, lines, title, artist, colorHex);
        }

        /** 开始播放 → 起前台服务（锁屏/后台不中断），同时把歌词上锁屏 */
        @JavascriptInterface
        public void playerStart(String lines, String title, String artist, String colorHex) {
            sendToService(LyricService.ACTION_START, lines, title, artist, colorHex);
        }

        /** 停止播放 → 收起通知并停掉服务 */
        @JavascriptInterface
        public void playerStop() {
            isPlaying = false;
            try {
                Intent i = new Intent(MainActivity.this, LyricService.class);
                i.setAction(LyricService.ACTION_STOP);
                startService(i);
            } catch (Exception ignored) {
            }
        }

        /**
         * 播放信息 → 原生 MediaSession（耳机上一首/下一首、车机显示歌名歌手封面）。
         * 服务没在跑时直接忽略，避免平白无故冒出通知。
         */
        @JavascriptInterface
        public void mediaMeta(String title, String artist, String album, String cover,
                              double durMs, double posMs, boolean playing) {
            if (!LyricService.isAlive()) return;
            Intent i = new Intent(MainActivity.this, LyricService.class);
            i.setAction(LyricService.ACTION_META);
            i.putExtra("title", title == null ? "" : title);
            i.putExtra("artist", artist == null ? "" : artist);
            i.putExtra("album", album == null ? "" : album);
            i.putExtra("cover", cover == null ? "" : cover);
            i.putExtra("durMs", (long) durMs);
            i.putExtra("posMs", (long) posMs);
            i.putExtra("playing", playing);
            startServiceSafe(i);
        }

        /** 服务器地址 + 飞牛音乐账号一起保存（两个都必填，前端已校验，这里再兜一层） */
        @JavascriptInterface
        public void save(String url, String user) {
            String u = url == null ? "" : url.trim();
            String name = user == null ? "" : user.trim();
            if (u.isEmpty() || name.isEmpty()) return;
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
            prefs.edit().putString(KEY_URL, u).putString(KEY_USER, name).apply();
            final String target = buildUrl(u, name);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    web.loadUrl(target);
                }
            });
        }

        @JavascriptInterface
        public void saveLocal(String url) {
            String u = url == null ? "" : url.trim();
            if (u.isEmpty()) return;
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
            prefs.edit().putString(KEY_URL, u).putString(KEY_USER, "").apply();
            final String target = u + (u.contains("?") ? "&" : "?") + "local=1";
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    web.loadUrl(target);
                }
            });
        }

        @JavascriptInterface
        public void reload() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String u = prefs.getString(KEY_URL, "");
                    String name = prefs.getString(KEY_USER, "");
                    if (u != null && !u.trim().isEmpty()) web.loadUrl(buildUrl(u, name));
                    else web.reload();
                }
            });
        }
    }

    /* ---------------- 返回键：先让网页退（关弹层 / 回首页），首页才弹退出确认 ---------------- */

    @Override
    public void onBackPressed() {
        // 连按三次返回 → 设置页（改服务器地址的后门）
        long now = System.currentTimeMillis();
        if (now - lastBackAt > 1500) backCount = 0;
        lastBackAt = now;
        backCount++;
        if (backCount >= 3) {
            backCount = 0;
            if (web != null) web.loadUrl(SETUP_PAGE);
            return;
        }
        if (web == null) {
            super.onBackPressed();
            return;
        }
        // 让网页自己决定：它处理了就到此为止，返回 'home' 说明已经在首页 → 弹退出确认
        web.evaluateJavascript("(window.maBack ? window.maBack() : 'home')", value -> {
            if (value == null || !value.contains("handled")) showExitDialog();
        });
    }

    /** 退出确认：不直接退出 App */
    private void showExitDialog() {
        if (exitDialog != null && exitDialog.isShowing()) return;
        String msg = isPlaying
                ? "确定要关闭「NAS歌下载」吗？\n退出后正在播放的音乐会停止。"
                : "确定要关闭「NAS歌下载」吗？";
        exitDialog = new AlertDialog.Builder(this)
                .setTitle("退出应用")
                .setMessage(msg)
                .setPositiveButton("退出", (d, w) -> {
                    try {
                        Intent i = new Intent(MainActivity.this, LyricService.class);
                        i.setAction(LyricService.ACTION_STOP);
                        startService(i);
                    } catch (Exception ignored) {
                    }
                    finish();
                })
                .setNegativeButton("取消", (d, w) -> {
                })
                .create();
        exitDialog.show();
    }

    @Override
    protected void onPause() {
        // 播放中不要挂起 WebView：否则锁屏/切后台后音频会被暂停（前台服务在保活）
        if (web != null && !isPlaying) web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (INSTANCE.get() == this) INSTANCE = new WeakReference<>(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
