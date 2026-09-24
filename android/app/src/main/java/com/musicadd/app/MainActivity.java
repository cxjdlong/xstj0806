package com.musicadd.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * 添加歌曲 · 安卓壳（在线 WebView）
 *
 * 直接加载 NAS 上的 music-add 网页（默认 http://192.168.10.10:19018），
 * 页面本身已做手机自适应，壳层只负责：全屏显示、禁用缩放/回弹、加载失败时给出改地址入口。
 *
 * 后门：连按返回键 3 次（1.5 秒内）= 打开服务器地址设置页。
 */
public class MainActivity extends Activity {

    private static final String DEFAULT_URL = "https://music.dx66.top:8888/";
    private static final String PREFS = "musicadd";
    private static final String KEY_URL = "server_url";
    private static final String KEY_USER = "fnos_user";
    private static final String SETUP_PAGE = "file:///android_asset/setup.html";

    private WebView web;
    private SharedPreferences prefs;
    private long lastBackAt = 0L;
    private int backCount = 0;
    private boolean isPlaying = false;   // 播放中就不挂起 WebView（锁屏/后台继续放）

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        web.setBackgroundColor(0xFF0F0F0F);
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
        if (saved == null || saved.trim().isEmpty() || user == null || user.trim().isEmpty()) {
            web.loadUrl(SETUP_PAGE);          // 地址或账号没填全 → 设置页（两个都必须填）
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

    /** 把歌词/播放状态丢给前台服务（锁屏通知） */
    private void sendToService(String action, String lyric, String title, String artist, String colorHex) {
        isPlaying = true;
        Intent i = new Intent(this, LyricService.class);
        i.setAction(action);
        i.putExtra("lyric", lyric == null ? "" : lyric);
        i.putExtra("title", title == null ? "" : title);
        i.putExtra("artist", artist == null ? "" : artist);
        i.putExtra("color", parseColor(colorHex));
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

    /** 暴露给内置设置页的桥 */
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

        /** 网页歌词换行 → 更新锁屏通知上的歌词 */
        @JavascriptInterface
        public void lyric(String line, String title, String artist, String colorHex) {
            sendToService(LyricService.ACTION_UPDATE, line, title, artist, colorHex);
        }

        /** 开始播放 → 起前台服务（锁屏/后台不中断），同时把歌词上锁屏 */
        @JavascriptInterface
        public void playerStart(String line, String title, String artist, String colorHex) {
            sendToService(LyricService.ACTION_START, line, title, artist, colorHex);
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            long now = System.currentTimeMillis();
            if (now - lastBackAt > 1500) backCount = 0;
            lastBackAt = now;
            backCount++;
            if (backCount >= 3) {          // 连按三次 → 设置页
                backCount = 0;
                web.loadUrl(SETUP_PAGE);
                return true;
            }
            if (web != null && web.canGoBack()) {
                web.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
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
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
