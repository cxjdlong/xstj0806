package com.musicadd.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;

/**
 * 播放前台服务：让网页里的音频在锁屏/后台继续播，并在锁屏通知上显示歌词。
 *
 * 通知布局是自定义 RemoteViews（lyric_notify.xml）：背板 60% 透明，歌词字色由网页传入。
 */
public class LyricService extends Service {

    public static final String ACTION_START = "com.musicadd.app.PLAYER_START";
    public static final String ACTION_STOP = "com.musicadd.app.PLAYER_STOP";
    public static final String ACTION_UPDATE = "com.musicadd.app.LYRIC_UPDATE";

    private static final String CHANNEL_ID = "musicadd_play";
    private static final int NOTIFY_ID = 1001;

    private String title = "";
    private String artist = "";
    private String lyric = "";
    private int color = Color.WHITE;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

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
            if (intent.hasExtra("lyric")) lyric = nz(intent.getStringExtra("lyric"));
            if (intent.hasExtra("color")) color = intent.getIntExtra("color", Color.WHITE);
        }
        try {
            startForeground(NOTIFY_ID, build());
        } catch (Exception ignored) {
            // 没给通知权限等情况：不让服务崩掉
        }
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
        rv.setTextViewText(R.id.n_lyric, lyric.isEmpty() ? "♪" : lyric);
        rv.setTextColor(R.id.n_lyric, color);

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        rv.setOnClickPendingIntent(R.id.n_root, pi);

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

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
