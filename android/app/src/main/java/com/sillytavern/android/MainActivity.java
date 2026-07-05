package com.sillytavern.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class MainActivity extends BridgeActivity {
    private static final String TAG = "MainActivity";
    private static final int FILE_CHOOSER_RESULT_CODE = 1;
    private ValueCallback<Uri[]> filePathCallback;
    private static final String PREFS_NAME = "SillyTavernPrefs";
    private static final String KEY_AUTH_USER = "auth_user";
    private static final String KEY_AUTH_PASS = "auth_pass";
    private static final String KEY_BACKGROUND_MODE = "background_mode";

    private boolean isForeground = true;
    private int proxyPort = 0;
    private boolean webViewInitialized = false;

    // Receiver for proxy/streaming events from StreamingService
    private BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            switch (intent.getAction()) {
                case StreamingService.BROADCAST_KEEPALIVE_PING:
                    // Ping WebView to prevent JS engine throttling in background
                    try {
                        WebView wv = getBridge().getWebView();
                        if (wv != null) {
                            wv.post(() -> wv.evaluateJavascript("void(0)", null));
                        }
                    } catch (Exception ignored) {}
                    break;

                case StreamingService.BROADCAST_PROXY_READY:
                    proxyPort = intent.getIntExtra("proxyPort", 0);
                    String targetUrl = intent.getStringExtra("targetUrl");
                    Log.d(TAG, "Proxy ready: port=" + proxyPort + " target=" + targetUrl);
                    break;
            }
        }
    };

    private SharedPreferences getSafeSharedPreferences(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context, PREFS_NAME, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            e.printStackTrace();
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (v, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right,
                    Math.max(systemBars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });

        WindowInsetsControllerCompat windowInsetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (windowInsetsController != null) {
            windowInsetsController.setAppearanceLightStatusBars(false);
            windowInsetsController.setAppearanceLightNavigationBars(false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        isForeground = true;
        notifyForegroundState(true);

        WebView webView = this.getBridge().getWebView();
        if (webView == null) {
            Log.e(TAG, "WebView is null!");
            return;
        }

        // One-time WebView initialization (only on first resume)
        if (!webViewInitialized) {
            webViewInitialized = true;
            initWebView(webView);
        }

        // Re-register broadcast receiver (may have been unregistered in onPause)
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(StreamingService.BROADCAST_KEEPALIVE_PING);
        filter.addAction(StreamingService.BROADCAST_PROXY_READY);
        try {
            lbm.registerReceiver(serviceReceiver, filter);
        } catch (IllegalArgumentException e) {
            // Already registered
        }

        // Sync background service state
        syncBackgroundService();
    }

    private void initWebView(WebView webView) {
        Log.d(TAG, "Initializing WebView (one-time)");

        // Add JavaScript Interfaces
        webView.addJavascriptInterface(new AuthBridge(this), "AuthBridge");
        webView.addJavascriptInterface(new BackgroundBridge(this), "BackgroundBridge");

        // Enable Zoom Support
        WebSettings webSettings = webView.getSettings();
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);

        // Set custom WebViewClient to handle Basic Auth and proxy errors
        webView.setWebViewClient(new BridgeWebViewClient(this.getBridge()) {
            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                                                   String host, String realm) {
                SharedPreferences prefs = getSafeSharedPreferences(MainActivity.this);
                String user = prefs.getString(KEY_AUTH_USER, null);
                String pass = prefs.getString(KEY_AUTH_PASS, null);
                if (user != null && !user.isEmpty() && pass != null) {
                    handler.proceed(user, pass);
                } else {
                    super.onReceivedHttpAuthRequest(view, handler, host, realm);
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description,
                                         String failingUrl) {
                // If proxy connection failed, try to restart and reload
                if (failingUrl != null && failingUrl.contains("127.0.0.1:")) {
                    Log.w(TAG, "Proxy unreachable: " + description + ". Retrying...");
                    // Send broadcast to restart proxy
                    Intent restartIntent = new Intent(StreamingService.ACTION_SET_FOREGROUND);
                    restartIntent.putExtra("isForeground", true);
                    LocalBroadcastManager.getInstance(MainActivity.this)
                            .sendBroadcast(restartIntent);
                    // Reload after a short delay to let proxy restart
                    view.postDelayed(() -> view.reload(), 1500);
                    return;
                }
                super.onReceivedError(view, errorCode, description, failingUrl);
            }
        });

        // Set a custom WebChromeClient to handle permission requests and file selection
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                request.grant(request.getResources());
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                              WebChromeClient.FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        isForeground = false;
        notifyForegroundState(false);

        // Keep receiver registered if background mode is enabled (for keepalive pings)
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_BACKGROUND_MODE, false)) {
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceReceiver);
            } catch (IllegalArgumentException e) {
                // Receiver not registered
            }
        }
    }

    private void notifyForegroundState(boolean foreground) {
        Intent intent = new Intent(StreamingService.ACTION_SET_FOREGROUND);
        intent.putExtra("isForeground", foreground);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void syncBackgroundService() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_BACKGROUND_MODE, false);
        Intent serviceIntent = new Intent(this, StreamingService.class);
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } else {
            stopService(serviceIntent);
        }
    }

    // ===== JavaScript Bridges =====

    public class BackgroundBridge {
        Context mContext;

        BackgroundBridge(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void setBackgroundMode(boolean enabled) {
            SharedPreferences prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_BACKGROUND_MODE, enabled).apply();
            syncBackgroundService();
        }

        @JavascriptInterface
        public boolean isIgnoringBatteryOptimizations() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
                return pm.isIgnoringBatteryOptimizations(mContext.getPackageName());
            }
            return true;
        }

        @JavascriptInterface
        public void requestIgnoreBatteryOptimizations() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + mContext.getPackageName()));
                mContext.startActivity(intent);
            }
        }

        /**
         * Start the local proxy for the given target URL.
         * Called by JS when background mode is enabled and user wants to connect.
         * @param targetUrl The real SillyTavern server URL
         */
        @JavascriptInterface
        public void startProxy(String targetUrl) {
            Log.d(TAG, "startProxy called with: " + targetUrl);
            Intent intent = new Intent(StreamingService.ACTION_SET_TARGET_URL);
            intent.putExtra("targetUrl", targetUrl);
            LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
        }

        /**
         * Get the current proxy port, or 0 if not running.
         */
        @JavascriptInterface
        public int getProxyPort() {
            return proxyPort;
        }

        @JavascriptInterface
        public void setBufferLimit(int limitKb) {
            Intent intent = new Intent(StreamingService.ACTION_SET_BUFFER_LIMIT);
            intent.putExtra("limitKb", limitKb);
            LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
        }
    }

    public class AuthBridge {
        Context mContext;

        AuthBridge(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void setCredentials(String user, String pass) {
            SharedPreferences prefs = getSafeSharedPreferences(mContext);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_AUTH_USER, user);
            editor.putString(KEY_AUTH_PASS, pass);
            editor.apply();
        }

        @JavascriptInterface
        public void clearCredentials() {
            SharedPreferences prefs = getSafeSharedPreferences(mContext);
            SharedPreferences.Editor editor = prefs.edit();
            editor.remove(KEY_AUTH_USER);
            editor.remove(KEY_AUTH_PASS);
            editor.apply();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (filePathCallback == null) return;
            filePathCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        }
    }
}
