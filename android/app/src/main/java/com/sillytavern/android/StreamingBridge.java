package com.sillytavern.android;

import android.content.Intent;
import android.util.Log;
import android.webkit.JavascriptInterface;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.UUID;

public class StreamingBridge {
    private static final String TAG = "StreamingBridge";
    private final MainActivity activity;
    private final LocalBroadcastManager broadcastManager;

    public StreamingBridge(MainActivity activity) {
        this.activity = activity;
        this.broadcastManager = LocalBroadcastManager.getInstance(activity);
    }

    @JavascriptInterface
    public boolean isNativeStreamingSupported() {
        return true;
    }

    /**
     * Start a streaming request via the native service.
     * @param url The request URL
     * @param method HTTP method (POST, GET, etc.)
     * @param headersJson JSON object of headers (keys and values)
     * @param body Request body (for POST/PUT)
     * @return requestId for tracking this stream
     */
    @JavascriptInterface
    public String streamFetch(String url, String method, String headersJson, String body) {
        String requestId = UUID.randomUUID().toString();
        Log.d(TAG, "streamFetch called: " + requestId + " " + method + " " + url);

        Intent intent = new Intent(StreamingService.ACTION_START_STREAM);
        intent.putExtra("requestId", requestId);
        intent.putExtra("url", url);
        intent.putExtra("method", method != null ? method : "POST");
        intent.putExtra("headers", headersJson != null ? headersJson : "{}");
        intent.putExtra("body", body != null ? body : "");
        broadcastManager.sendBroadcast(intent);

        return requestId;
    }

    @JavascriptInterface
    public void cancelStream(String requestId) {
        Log.d(TAG, "cancelStream called: " + requestId);
        Intent intent = new Intent(StreamingService.ACTION_CANCEL_STREAM);
        intent.putExtra("requestId", requestId);
        broadcastManager.sendBroadcast(intent);
    }

    /**
     * Called by MainActivity when streaming data arrives from the service.
     * Pushes the data to JavaScript bridge callback.
     */
    public void pushDataToJs(String requestId, String chunk, boolean isBatch) {
        if (activity.getBridge() == null || activity.getBridge().getWebView() == null) return;

        String escapedChunk = escapeJsString(chunk);

        if (isBatch) {
            // For batch data, split and deliver line by line
            String js = "if(window.__st_bridge_onBatch){" +
                    "window.__st_bridge_onBatch('" + requestId + "', '" + escapedChunk + "');" +
                    "}";
            activity.getBridge().getWebView().post(() ->
                activity.getBridge().getWebView().evaluateJavascript(js, null)
            );
        } else {
            String js = "if(window.__st_bridge_onData){" +
                    "window.__st_bridge_onData('" + requestId + "', '" + escapedChunk + "');" +
                    "}";
            activity.getBridge().getWebView().post(() ->
                activity.getBridge().getWebView().evaluateJavascript(js, null)
            );
        }
    }

    public void pushDoneToJs(String requestId) {
        if (activity.getBridge() == null || activity.getBridge().getWebView() == null) return;

        String js = "if(window.__st_bridge_onDone){" +
                "window.__st_bridge_onDone('" + requestId + "');" +
                "}";
        activity.getBridge().getWebView().post(() ->
            activity.getBridge().getWebView().evaluateJavascript(js, null)
        );
    }

    public void pushErrorToJs(String requestId, String error) {
        if (activity.getBridge() == null || activity.getBridge().getWebView() == null) return;

        String escapedError = escapeJsString(error);
        String js = "if(window.__st_bridge_onError){" +
                "window.__st_bridge_onError('" + requestId + "', '" + escapedError + "');" +
                "}";
        activity.getBridge().getWebView().post(() ->
            activity.getBridge().getWebView().evaluateJavascript(js, null)
        );
    }

    private String escapeJsString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
