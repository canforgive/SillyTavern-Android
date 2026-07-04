package com.sillytavern.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class StreamingService extends Service {
    private static final String TAG = "StreamingService";
    private static final String CHANNEL_ID = "StreamingChannel";
    private static final int NOTIFICATION_ID = 2;
    private static final String PREFS_NAME = "SillyTavernPrefs";
    private static final String KEY_BACKGROUND_MODE = "background_mode";

    // Actions
    public static final String ACTION_SET_TARGET_URL = "com.sillytavern.android.SET_TARGET_URL";
    public static final String ACTION_SET_FOREGROUND = "com.sillytavern.android.SET_FOREGROUND";
    public static final String ACTION_SET_BUFFER_LIMIT = "com.sillytavern.android.SET_BUFFER_LIMIT";
    public static final String ACTION_START_STREAM = "com.sillytavern.android.START_STREAM";
    public static final String ACTION_CANCEL_STREAM = "com.sillytavern.android.CANCEL_STREAM";
    public static final String BROADCAST_KEEPALIVE_PING = "com.sillytavern.android.KEEPALIVE_PING";
    public static final String BROADCAST_PROXY_READY = "com.sillytavern.android.PROXY_READY";

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private LocalProxyServer proxyServer;
    private LocalBroadcastManager broadcastManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable keepaliveRunnable;
    private static final int KEEPALIVE_INTERVAL_MS = 5000;

    private boolean isForeground = true;
    private int proxyPort = 0;

    private BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case ACTION_SET_TARGET_URL:
                    String targetUrl = intent.getStringExtra("targetUrl");
                    if (targetUrl != null && !targetUrl.isEmpty()) {
                        setTargetAndStartProxy(targetUrl);
                    }
                    break;

                case ACTION_SET_FOREGROUND:
                    boolean fg = intent.getBooleanExtra("isForeground", true);
                    setForegroundMode(fg);
                    break;

                case ACTION_SET_BUFFER_LIMIT:
                    int limitKb = intent.getIntExtra("limitKb", 100);
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putInt("buffer_limit_kb", limitKb).apply();
                    break;
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "StreamingService created");

        broadcastManager = LocalBroadcastManager.getInstance(this);
        createNotificationChannel();

        // WakeLock - prevent CPU sleep
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SillyTavern::Streaming");

        // WiFi Lock - prevent WiFi low-power mode
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SillyTavern::StreamingWifi");
        }

        // Keepalive ping to prevent WebView JS throttling in background
        keepaliveRunnable = new Runnable() {
            @Override
            public void run() {
                Intent pingIntent = new Intent(BROADCAST_KEEPALIVE_PING);
                broadcastManager.sendBroadcast(pingIntent);
                mainHandler.postDelayed(this, KEEPALIVE_INTERVAL_MS);
            }
        };

        // Create proxy server
        proxyServer = new LocalProxyServer();

        // Register control broadcast receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SET_TARGET_URL);
        filter.addAction(ACTION_SET_FOREGROUND);
        filter.addAction(ACTION_SET_BUFFER_LIMIT);
        broadcastManager.registerReceiver(controlReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SillyTavern")
                .setContentText("Proxy ready for background streaming")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
        if (wifiLock != null && !wifiLock.isHeld()) {
            wifiLock.acquire();
        }

        // Start keepalive pings
        if (keepaliveRunnable != null) {
            mainHandler.removeCallbacks(keepaliveRunnable);
            mainHandler.post(keepaliveRunnable);
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "StreamingService destroyed");

        // Stop proxy
        if (proxyServer != null) {
            proxyServer.stop();
        }

        // Stop keepalive
        if (keepaliveRunnable != null) {
            mainHandler.removeCallbacks(keepaliveRunnable);
        }

        broadcastManager.unregisterReceiver(controlReceiver);

        if (wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void setTargetAndStartProxy(String targetUrl) {
        Log.d(TAG, "Setting target URL: " + targetUrl);
        proxyServer.setTarget(targetUrl);
        proxyPort = proxyServer.start();
        proxyServer.setForegroundMode(isForeground);

        if (proxyPort > 0) {
            // Notify MainActivity that proxy is ready
            Intent readyIntent = new Intent(BROADCAST_PROXY_READY);
            readyIntent.putExtra("proxyPort", proxyPort);
            readyIntent.putExtra("targetUrl", targetUrl);
            broadcastManager.sendBroadcast(readyIntent);

            updateNotification("Proxy active on port " + proxyPort);
            Log.i(TAG, "Proxy ready on port " + proxyPort);
        } else {
            Log.e(TAG, "Failed to start proxy");
            updateNotification("Proxy failed to start");
        }
    }

    private void setForegroundMode(boolean fg) {
        isForeground = fg;
        if (proxyServer != null) {
            proxyServer.setForegroundMode(fg);
        }
        Log.d(TAG, "Foreground mode: " + fg);

        if (!fg) {
            int streams = proxyServer != null ? proxyServer.getActiveStreamCount() : 0;
            if (streams > 0) {
                updateNotification("AI responding... (" + streams + " active)");
            }
        } else {
            updateNotification("Proxy active on port " + proxyPort);
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SillyTavern")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        nm.notify(NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SillyTavern Streaming Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows proxy and streaming status");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
