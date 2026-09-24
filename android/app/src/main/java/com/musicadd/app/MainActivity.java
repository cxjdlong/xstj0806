package com.musicadd.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
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

    private static final String DEFAULT_URL = "http://192.168.10.10:19018";
    private static final String PREFS = "musicadd";
    private static final String KEY_URL = "server_url";
    private static final String SETUP_PAGE = "file:///android_asset/setup.html";

    private WebView web;
    private SharedPreferences prefs;
    private long lastBackAt = 0L;
    private int backCount = 0;

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
        if (saved == null || saved.trim().isEmpty()) {
            web.loadUrl(SETUP_PAGE);
        } else {
            web.loadUrl(saved);
        }
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
        public void save(String url) {
            String u = url == null ? "" : url.trim();
            if (u.isEmpty()) u = DEFAULT_URL;
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
            prefs.edit().putString(KEY_URL, u).apply();
            final String target = u;
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
                    web.reload();
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
        if (web != null) web.onPause();
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
