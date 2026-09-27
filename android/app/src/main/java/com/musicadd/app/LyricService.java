package com.musicadd.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 播放前台服务：
 * 1) 让网页里的音频在锁屏/后台继续播，并在锁屏通知上显示多行滚动歌词；
 * 2) 维持一个原生 MediaSession —— 蓝牙耳机/线控的上一首、下一首、播放暂停由系统派发到这里，
 *    转发给网页执行；车机也能读到歌名、歌手、专辑、封面和进度。
 *
 * 通知布局 lyric_notify.xml：背板 60% 透明；中间那行是「当前句」（大字 + 网页传来的颜色），
 * 上下各两行是前后文（小字淡显）—— 每换一句就整体上移，形成滚动效果。
 */
public class LyricService extends Service {

    public static final String ACTION_START = "com.musicadd.app.PLAYER_START";
    public static final String ACTION_STOP = "com.musicadd.app.PLAYER_STOP";
    public static final String ACTION_UPDATE = "com.musicadd.app.LYRIC_UPDATE";
    public static final String ACTION_META = "com.musicadd.app.MEDIA_META";
    /** 通知里的上一首/播放/下一首/模式按钮（等价于按耳机键） */
    public static final String ACTION_KEY = "com.musicadd.app.MEDIA_KEY";
    /** 播放模式变化（顺/循/单/随） */
    public static final String ACTION_MODE = "com.musicadd.app.MEDIA_MODE";

    /** 通知按钮里的“模式”虚拟键码 */
    private static final int KEY_MODE = 0;

    private static final String CHANNEL_ID = "musicadd_play";
    private static final int NOTIFY_ID = 1001;
    /** 网页一次推 3 行（上 1 + 当前句 + 下 1），锁屏卡片只显示中间那一行（当前句） */
    private static final int ROWS = 3;
    private static final int CUR_ROW = 1;
    /** 进度条两头的 ±10 秒按钮 */
    private static final int KEY_BACK10 = -10;
    private static final int KEY_FWD10 = 10;
    /** 网页把多行歌词用换行符拼成一串传过来（歌词本身不含换行，安全） */
    private static final String SEP = "\n";

    /** 服务是否在跑（网页侧判断要不要推 mediaMeta） */
    private static volatile boolean ALIVE = false;

    public static boolean isAlive() {
        return ALIVE;
    }

    private String title = "";
    private String artist = "";
    private String album = "";
    private long durationMs = 0L;
    private long positionMs = 0L;
    private boolean playing = false;
    private String coverUrl = "";
    private String loadedCoverUrl = "";
    private Bitmap coverBmp;
    private Bitmap fallbackCover;      // 没有封面时用 App 图标顶位
    /** 播放模式：order/loop/one/shuffle（由网页同步过来，通知上的模式键显示用） */
    private String mode = "loop";
    private String[] lines = new String[]{"", "", ""};
    private int color = Color.WHITE;

    private MediaSession session;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ALIVE = true;
        createChannel();
        createSession();
    }

    @Override
    public void onDestroy() {
        ALIVE = false;
        if (session != null) {
            try {
                session.setActive(false);
                session.release();
            } catch (Exception ignored) {
            }
            session = null;
        }
        super.onDestroy();
    }

    /* ---------------- 原生 MediaSession：耳机线控 / 车机 ---------------- */

    private void createSession() {
        try {
            session = new MediaSession(this, "NAS歌下载");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    MainActivity.sendMediaCmd("play");
                    playing = true;
                    pushSession();
                }

                @Override
                public void onPause() {
                    MainActivity.sendMediaCmd("pause");
                    playing = false;
                    pushSession();
                }

                @Override
                public void onStop() {
                    MainActivity.sendMediaCmd("pause");
                    playing = false;
                    pushSession();
                }

                @Override
                public void onSkipToNext() {
                    MainActivity.sendMediaCmd("next");
                }

                @Override
                public void onSkipToPrevious() {
                    MainActivity.sendMediaCmd("prev");
                }

                @Override
                public void onSeekTo(long pos) {
                    MainActivity.sendMediaCmd("seek:" + (pos / 1000));
                }
            });
            session.setActive(true);
        } catch (Exception ignored) {
        }
    }

    /** 把当前歌曲/播放状态写进 MediaSession（车机与耳机按键都读这个） */
    private void pushSession() {
        if (session == null) return;
        try {
            MediaMetadata.Builder md = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM,
                            album.isEmpty() ? getString(R.string.app_name) : album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0L, durationMs));
            if (!coverUrl.isEmpty()) {
                md.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, coverUrl);
            }
            if (coverBmp != null) {
                md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, coverBmp);
            }
            session.setMetadata(md.build());

            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                    | PlaybackState.ACTION_STOP;
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            Math.max(0L, positionMs), 1.0f)
                    .build());
        } catch (Exception ignored) {
        }
    }

    /** 封面异步下载（失败就只留封面 URL，车机自己取） */
    private void loadCover(final String url) {
        if (url == null || url.isEmpty() || url.equals(loadedCoverUrl)) return;
        loadedCoverUrl = url;
        final Handler h = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(8000);
                    c.setInstanceFollowRedirects(true);
                    InputStream in = c.getInputStream();
                    bmp = BitmapFactory.decodeStream(in);
                    in.close();
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
                final Bitmap got = bmp;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (got != null) {
                            coverBmp = scaleCover(got);
                            pushSession();
                            refreshNotification();     // 锁屏卡片左上角的封面图
                        }
                    }
                });
            }
        }).start();
    }

    /* ---------------- 通知与生命周期 ---------------- */

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                try {
                    stopForeground(true);
                } catch (Exception ignored) {
                }
                stopSelf();
                return START_NOT_STICKY;
            }
            if (intent.hasExtra("title")) title = nz(intent.getStringExtra("title"));
            if (intent.hasExtra("artist")) artist = nz(intent.getStringExtra("artist"));
            if (ACTION_META.equals(action)) {
                if (intent.hasExtra("album")) album = nz(intent.getStringExtra("album"));
                if (intent.hasExtra("cover")) coverUrl = nz(intent.getStringExtra("cover"));
                if (intent.hasExtra("durMs")) durationMs = intent.getLongExtra("durMs", 0L);
                if (intent.hasExtra("posMs")) positionMs = intent.getLongExtra("posMs", 0L);
                if (intent.hasExtra("playing")) playing = intent.getBooleanExtra("playing", playing);
                if (!coverUrl.isEmpty()) loadCover(coverUrl);
            } else if (ACTION_KEY.equals(action)) {
                int kc = intent.getIntExtra("keyCode", 0);
                if (kc == KEY_MODE) {
                    MainActivity.sendMediaCmd("mode");     // 网页切完模式会把新模式回传过来
                } else if (kc == KEY_BACK10 || kc == KEY_FWD10) {
                    long target = positionMs + kc * 1000L;   // 相对当前位置跳 ±10 秒
                    if (target < 0) target = 0;
                    if (durationMs > 0 && target > durationMs) target = durationMs;
                    positionMs = target;
                    MainActivity.sendMediaCmd("seek:" + (target / 1000));
                    refreshNotification();                   // 进度条立刻跟上
                } else if (kc == android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                    MainActivity.sendMediaCmd("prev");
                } else if (kc == android.view.KeyEvent.KEYCODE_MEDIA_NEXT) {
                    MainActivity.sendMediaCmd("next");
                } else {                       // 播放 / 暂停
                    playing = !playing;
                    MainActivity.sendMediaCmd(playing ? "play" : "pause");
                }
                try {
                    startForeground(NOTIFY_ID, build());
                } catch (Exception ignored) {
                }
                pushSession();
                return START_STICKY;
            } else if (ACTION_MODE.equals(action)) {
                if (intent.hasExtra("mode")) mode = nz(intent.getStringExtra("mode"));
                refreshNotification();
                return START_STICKY;
            } else if (intent.hasExtra("lines")) {
                String raw = nz(intent.getStringExtra("lines"));
                String[] parts = raw.split(SEP, -1);
                for (int i = 0; i < ROWS; i++) {
                    lines[i] = (i < parts.length) ? nz(parts[i]) : "";
                }
                if (ACTION_START.equals(action)) playing = true;
            }
            if (intent.hasExtra("color")) color = intent.getIntExtra("color", Color.WHITE);
        }
        try {
            startForeground(NOTIFY_ID, build());
        } catch (Exception ignored) {
            // 没给通知权限等情况：不让服务崩掉
        }
        pushSession();
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.notify_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification build() {
        RemoteViews rv = new RemoteViews(getPackageName(), R.layout.lyric_notify);
        rv.setTextViewText(R.id.n_title, title.isEmpty() ? getString(R.string.app_name) : title);
        rv.setTextViewText(R.id.n_artist, artist);

        // 左上角封面图（拿不到封面就用 App 图标顶位）
        Bitmap cover = (coverBmp != null) ? coverBmp : fallbackCover();
        if (cover != null) rv.setImageViewBitmap(R.id.n_cover, cover);

        // 歌词行：文字色 + 淡底色都跟随 App 里选的歌词颜色
        String cur = (CUR_ROW < lines.length && lines[CUR_ROW] != null && !lines[CUR_ROW].trim().isEmpty())
                ? lines[CUR_ROW] : "";
        rv.setTextViewText(R.id.n_lyric, cur.isEmpty() ? "♪" : cur);
        rv.setTextColor(R.id.n_lyric, color);
        rv.setInt(R.id.n_lyric, "setBackgroundColor", (color & 0x00FFFFFF) | 0x2E000000);

        // 进度条 + 两端时间（数据来自网页每 2 秒上报的 pos/dur）
        int pct = 0;
        if (durationMs > 0) {
            pct = (int) Math.max(0, Math.min(1000, positionMs * 1000 / durationMs));
        }
        rv.setProgressBar(R.id.n_prog, 1000, pct, false);
        rv.setTextViewText(R.id.n_cur, clock(positionMs));
        rv.setTextViewText(R.id.n_dur, durationMs > 0 ? clock(durationMs) : "--:--");
        rv.setOnClickPendingIntent(R.id.n_prog, openAppIntent());
        rv.setOnClickPendingIntent(R.id.n_back10, mediaKeyIntent(NOTIFY_ID * 10 + 5, KEY_BACK10));
        rv.setOnClickPendingIntent(R.id.n_fwd10, mediaKeyIntent(NOTIFY_ID * 10 + 6, KEY_FWD10));

        // 播放模式键：顺 / 循 / 单 / 随（随机时按钮变蓝）
        rv.setTextViewText(R.id.n_mode, modeLabel());
        rv.setInt(R.id.n_mode, "setBackgroundResource",
                "shuffle".equals(mode) ? R.drawable.nmode_bg_on : R.drawable.nmode_bg);

        PendingIntent pi = openAppIntent();
        rv.setOnClickPendingIntent(R.id.n_root, pi);

        // 上一首 / 播放暂停 / 下一首 / 模式切换：跟耳机线控走同一条路
        rv.setOnClickPendingIntent(R.id.n_prev, mediaKeyIntent(NOTIFY_ID * 10 + 1,
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        rv.setOnClickPendingIntent(R.id.n_toggle, mediaKeyIntent(NOTIFY_ID * 10 + 2,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        rv.setOnClickPendingIntent(R.id.n_next, mediaKeyIntent(NOTIFY_ID * 10 + 3,
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT));
        rv.setOnClickPendingIntent(R.id.n_mode, mediaKeyIntent(NOTIFY_ID * 10 + 4, KEY_MODE));

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setCustomContentView(rv)
                .setCustomBigContentView(rv)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    /** 点卡片（或进度条）→ 打开 App，去里面拖进度 */
    private PendingIntent openAppIntent() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 1, open, flags);
    }

    /** 毫秒 → m:ss */
    private static String clock(long ms) {
        long sec = Math.max(0, ms) / 1000;
        return (sec / 60) + ":" + String.format("%02d", sec % 60);
    }

    /** 重画通知（歌词换行、封面到位、模式切换都走它） */
    private void refreshNotification() {
        try {
            startForeground(NOTIFY_ID, build());
        } catch (Exception ignored) {
        }
    }

    /** 模式 → 锁屏卡片上的按钮文字 */
    private String modeLabel() {
        if ("order".equals(mode)) return "顺";
        if ("one".equals(mode)) return "单";
        if ("shuffle".equals(mode)) return "随";
        return "循";
    }

    /** 封面缩到通知够用的尺寸（RemoteViews 传大图会超 binder 限制） */
    private static Bitmap scaleCover(Bitmap src) {
        try {
            int size = 200;
            if (src.getWidth() <= size && src.getHeight() <= size) return src;
            return Bitmap.createScaledBitmap(src, size, size, true);
        } catch (Exception e) {
            return src;
        }
    }

    private Bitmap fallbackCover() {
        if (fallbackCover == null) {
            try {
                fallbackCover = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
            } catch (Exception ignored) {
            }
        }
        return fallbackCover;
    }

    /** 通知里的按钮 → 直接调 MediaSession 回调（等价于按耳机键） */
    private PendingIntent mediaKeyIntent(int reqCode, final int keyCode) {
        Intent i = new Intent(this, LyricService.class);
        i.setAction("com.musicadd.app.MEDIA_KEY");
        i.putExtra("keyCode", keyCode);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, reqCode, i, flags);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
