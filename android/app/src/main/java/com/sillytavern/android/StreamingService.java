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
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

public class StreamingService extends Service {
    private static final String TAG = "StreamingService";
    private static final String CHANNEL_ID = "StreamingChannel";
    private static final int NOTIFICATION_ID = 2;
    private static final String PREFS_NAME = "SillyTavernPrefs";
    private static final String KEY_AUTH_USER = "auth_user";
    private static final String KEY_AUTH_PASS = "auth_pass";
    private static final String KEY_BACKGROUND_MODE = "background_mode";
    private static final String KEY_BUFFER_LIMIT = "buffer_limit_kb";

    public static final String ACTION_START_STREAM = "com.sillytavern.android.START_STREAM";
    public static final String ACTION_CANCEL_STREAM = "com.sillytavern.android.CANCEL_STREAM";
    public static final String ACTION_SET_FOREGROUND = "com.sillytavern.android.SET_FOREGROUND";
    public static final String ACTION_SET_BUFFER_LIMIT = "com.sillytavern.android.SET_BUFFER_LIMIT";
    public static final String BROADCAST_STREAM_DATA = "com.sillytavern.android.STREAM_DATA";
    public static final String BROADCAST_STREAM_DONE = "com.sillytavern.android.STREAM_DONE";
    public static final String BROADCAST_STREAM_ERROR = "com.sillytavern.android.STREAM_ERROR";
    public static final String BROADCAST_KEEPALIVE_PING = "com.sillytavern.android.KEEPALIVE_PING";

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private OkHttpClient httpClient;
    private boolean isForeground = true;
    private final ConcurrentHashMap<String, StreamingRequest> activeStreams = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LocalBroadcastManager broadcastManager;
    private int maxBufferSize = 100 * 1024; // Default 100KB per stream
    private Runnable keepaliveRunnable;
    private static final int KEEPALIVE_INTERVAL_MS = 5000; // Ping every 5 seconds

    private static class StreamingRequest {
        Call call;
        List<String> bufferedChunks = new ArrayList<>();
        String url;
        String lastToken = "";
        int bufferedBytes = 0;
    }

    private BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            String action = intent.getAction();
            if (action == null) return;

            String requestId = intent.getStringExtra("requestId");

            switch (action) {
                case ACTION_START_STREAM:
                    String url = intent.getStringExtra("url");
                    String method = intent.getStringExtra("method");
                    String headersJson = intent.getStringExtra("headers");
                    String body = intent.getStringExtra("body");
                    startStreaming(requestId, url, method, headersJson, body);
                    break;

                case ACTION_CANCEL_STREAM:
                    cancelStreaming(requestId);
                    break;

                case ACTION_SET_FOREGROUND:
                    boolean fg = intent.getBooleanExtra("isForeground", true);
                    setForegroundMode(fg);
                    break;

                case ACTION_SET_BUFFER_LIMIT:
                    int limitKb = intent.getIntExtra("limitKb", 100);
                    maxBufferSize = limitKb * 1024;
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putInt(KEY_BUFFER_LIMIT, limitKb).apply();
                    Log.d(TAG, "Buffer limit set to " + limitKb + " KB");
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

        // Load buffer limit from preferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        maxBufferSize = prefs.getInt(KEY_BUFFER_LIMIT, 100) * 1024;

        // WakeLock - prevent CPU sleep
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SillyTavern::Streaming");

        // WiFi Lock - prevent WiFi low-power mode
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SillyTavern::StreamingWifi");
        }

        // OkHttp client with connection pool and timeouts
        httpClient = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)  // No read timeout for streaming
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        // Keepalive ping to prevent WebView JS throttling in background
        keepaliveRunnable = new Runnable() {
            @Override
            public void run() {
                Intent pingIntent = new Intent(BROADCAST_KEEPALIVE_PING);
                broadcastManager.sendBroadcast(pingIntent);
                mainHandler.postDelayed(this, KEEPALIVE_INTERVAL_MS);
            }
        };

        // Register control broadcast receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_START_STREAM);
        filter.addAction(ACTION_CANCEL_STREAM);
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
                .setContentText("Ready for background streaming")
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

        // Start keepalive pings to prevent WebView JS throttling
        if (keepaliveRunnable != null) {
            mainHandler.removeCallbacks(keepaliveRunnable);
            mainHandler.post(keepaliveRunnable);
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "StreamingService destroyed");
        broadcastManager.unregisterReceiver(controlReceiver);

        // Cancel all active streams
        for (StreamingRequest req : activeStreams.values()) {
            if (req.call != null) {
                req.call.cancel();
            }
        }
        activeStreams.clear();

        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }

        // Stop keepalive pings
        if (keepaliveRunnable != null) {
            mainHandler.removeCallbacks(keepaliveRunnable);
        }

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void setForegroundMode(boolean fg) {
        isForeground = fg;
        Log.d(TAG, "Foreground mode: " + fg);

        if (fg) {
            // Flush buffered data when coming to foreground
            for (String requestId : activeStreams.keySet()) {
                flushBufferedData(requestId);
            }
        }
    }

    private void startStreaming(String requestId, String url, String method, String headersJson, String body) {
        Log.d(TAG, "Starting stream: " + requestId + " -> " + url);

        // Build request
        Request.Builder requestBuilder = new Request.Builder().url(url);

        // Parse headers
        try {
            JSONObject headersObj = new JSONObject(headersJson);
            Iterator<String> keys = headersObj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = headersObj.getString(key);
                // Skip hop-by-hop and restricted headers
                if ("host".equalsIgnoreCase(key) || "connection".equalsIgnoreCase(key)
                        || "transfer-encoding".equalsIgnoreCase(key)) {
                    continue;
                }
                requestBuilder.addHeader(key, value);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing headers: " + e.getMessage());
        }

        // Add auth if available
        addAuthHeaders(requestBuilder, url);

        // Set body
        if (body != null && !body.isEmpty() && ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))) {
            MediaType jsonType = MediaType.parse("application/json; charset=utf-8");
            requestBuilder.method(method, RequestBody.create(body, jsonType));
        } else {
            requestBuilder.method(method, null);
        }

        Request request = requestBuilder.build();

        // Create tracking entry
        StreamingRequest streamReq = new StreamingRequest();
        streamReq.url = url;
        activeStreams.put(requestId, streamReq);

        // Execute request
        Call call = httpClient.newCall(request);
        streamReq.call = call;

        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (call.isCanceled()) return;
                Log.e(TAG, "Stream failed: " + e.getMessage());
                sendError(requestId, e.getMessage());
                activeStreams.remove(requestId);
                updateNotification();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    sendError(requestId, "Empty response body");
                    activeStreams.remove(requestId);
                    updateNotification();
                    return;
                }

                String contentType = response.header("Content-Type", "");
                boolean isEventStream = contentType.contains("text/event-stream");

                try (BufferedSource source = responseBody.source()) {
                    String line;
                    while ((line = source.readUtf8Line()) != null) {
                        if (call.isCanceled()) break;

                        if (line.isEmpty()) continue;

                        // Parse SSE format: "data: {...}"
                        String data = line;
                        if (isEventStream && line.startsWith("data: ")) {
                            data = line.substring(6);
                            if ("[DONE]".equals(data)) {
                                break;
                            }
                        }

                        streamReq.lastToken = data;
                        addChunk(streamReq, data);

                        if (isForeground) {
                            sendData(requestId, data);
                        }

                        // Update notification with latest token
                        updateNotificationText(requestId);
                    }
                } catch (IOException e) {
                    if (!call.isCanceled()) {
                        Log.e(TAG, "Error reading stream: " + e.getMessage());
                        sendError(requestId, e.getMessage());
                        activeStreams.remove(requestId);
                        updateNotification();
                        return;
                    }
                }

                if (!call.isCanceled()) {
                    sendDone(requestId);
                    activeStreams.remove(requestId);
                    updateNotification();
                }
            }
        });
    }

    /**
     * Add a chunk to the buffer, trimming old chunks if maxBufferSize is exceeded.
     */
    private void addChunk(StreamingRequest req, String data) {
        int chunkSize = data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        req.bufferedBytes += chunkSize;
        req.bufferedChunks.add(data);

        // If we're in background mode and buffer exceeds limit, trim old chunks
        if (!isForeground && maxBufferSize > 0) {
            while (req.bufferedBytes > maxBufferSize && req.bufferedChunks.size() > 1) {
                String removed = req.bufferedChunks.remove(0);
                req.bufferedBytes -= removed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
        }
    }

    private void cancelStreaming(String requestId) {
        StreamingRequest req = activeStreams.remove(requestId);
        if (req != null && req.call != null) {
            req.call.cancel();
        }
        updateNotification();
    }

    private void sendData(String requestId, String chunk) {
        Intent intent = new Intent(BROADCAST_STREAM_DATA);
        intent.putExtra("requestId", requestId);
        intent.putExtra("chunk", chunk);
        broadcastManager.sendBroadcast(intent);
    }

    private void sendDone(String requestId) {
        Intent intent = new Intent(BROADCAST_STREAM_DONE);
        intent.putExtra("requestId", requestId);
        broadcastManager.sendBroadcast(intent);
    }

    private void sendError(String requestId, String error) {
        Intent intent = new Intent(BROADCAST_STREAM_ERROR);
        intent.putExtra("requestId", requestId);
        intent.putExtra("error", error);
        broadcastManager.sendBroadcast(intent);
    }

    private void flushBufferedData(String requestId) {
        StreamingRequest req = activeStreams.get(requestId);
        if (req != null && !req.bufferedChunks.isEmpty()) {
            // Send all buffered chunks at once via a batch event
            StringBuilder sb = new StringBuilder();
            for (String chunk : req.bufferedChunks) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(chunk);
            }
            Intent intent = new Intent(BROADCAST_STREAM_DATA);
            intent.putExtra("requestId", requestId);
            intent.putExtra("chunk", sb.toString());
            intent.putExtra("isBatch", true);
            broadcastManager.sendBroadcast(intent);
            req.bufferedChunks.clear();
        }
    }

    private void updateNotification() {
        int count = activeStreams.size();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        String text;
        if (count > 0) {
            text = "AI responding... (" + count + " active)";
        } else {
            text = "Ready for background streaming";
        }

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SillyTavern")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pendingIntent)
                .setPriority(activeStreams.isEmpty() ? NotificationCompat.PRIORITY_LOW : NotificationCompat.PRIORITY_DEFAULT)
                .setOngoing(true)
                .build();

        nm.notify(NOTIFICATION_ID, notification);
    }

    private void updateNotificationText(String requestId) {
        StreamingRequest req = activeStreams.get(requestId);
        if (req == null || req.lastToken.isEmpty()) return;

        // Extract a preview of the last token for notification
        String preview = req.lastToken;
        if (preview.length() > 60) {
            preview = preview.substring(0, 60) + "...";
        }

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SillyTavern - AI Responding")
                .setContentText(preview)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOngoing(true)
                .build();

        nm.notify(NOTIFICATION_ID, notification);
    }

    private void addAuthHeaders(Request.Builder requestBuilder, String url) {
        try {
            SharedPreferences prefs = getEncryptedPrefs();
            String user = prefs.getString(KEY_AUTH_USER, null);
            String pass = prefs.getString(KEY_AUTH_PASS, null);

            if (user != null && !user.isEmpty() && pass != null) {
                String credentials = okhttp3.Credentials.basic(user, pass);
                requestBuilder.addHeader("Authorization", credentials);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error adding auth headers: " + e.getMessage());
        }
    }

    private SharedPreferences getEncryptedPrefs() throws GeneralSecurityException, IOException {
        MasterKey masterKey = new MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        return EncryptedSharedPreferences.create(
                this,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SillyTavern Streaming Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Shows AI streaming status");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
