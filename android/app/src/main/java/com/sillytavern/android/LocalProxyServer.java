package com.sillytavern.android;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * Lightweight HTTP reverse proxy that forwards WebView requests to the real
 * SillyTavern server. Runs inside StreamingService so network connections
 * continue working even when the app is in background.
 */
public class LocalProxyServer {
    private static final String TAG = "LocalProxyServer";
    private static final int THREAD_POOL_SIZE = 8;

    private ServerSocket serverSocket;
    private String targetHost;
    private int targetPort;
    private String targetScheme = "http";
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean isForeground = new AtomicBoolean(true);
    private final ExecutorService threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    private final OkHttpClient httpClient;
    private int listenPort = 0;

    // Track active streaming connections for buffering
    private final ConcurrentHashMap<String, StreamContext> activeStreams = new ConcurrentHashMap<>();

    private static class StreamContext {
        final List<byte[]> buffer = new ArrayList<>();
        OutputStream clientOut;
        boolean clientConnected = true;
        final String requestId;
        long totalBuffered = 0;

        StreamContext(String requestId) {
            this.requestId = requestId;
        }
    }

    public LocalProxyServer() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)  // No timeout for streaming
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }

    /**
     * Configure the target server.
     */
    public void setTarget(String url) {
        try {
            URL parsed = new URL(url);
            this.targetScheme = parsed.getProtocol();
            this.targetHost = parsed.getHost();
            this.targetPort = parsed.getPort();
            if (this.targetPort == -1) {
                this.targetPort = "https".equals(targetScheme) ? 443 : 80;
            }
            Log.d(TAG, "Target set to " + targetScheme + "://" + targetHost + ":" + targetPort);
        } catch (Exception e) {
            Log.e(TAG, "Invalid target URL: " + url + " - " + e.getMessage());
        }
    }

    /**
     * Start the proxy server on a fixed port.
     * @return the port number, or 0 if failed
     */
    public int start() {
        if (running.get()) return listenPort;

        int fixedPort = 48765;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new java.net.InetSocketAddress("127.0.0.1", fixedPort), 50);
            listenPort = serverSocket.getLocalPort();
            running.set(true);

            // Accept connections in a background thread
            Thread acceptThread = new Thread(() -> {
                while (running.get()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        threadPool.submit(() -> handleClient(clientSocket));
                    } catch (IOException e) {
                        if (running.get()) {
                            Log.e(TAG, "Accept error: " + e.getMessage());
                        }
                    }
                }
            }, "ProxyAccept");
            acceptThread.setDaemon(true);
            acceptThread.start();

            Log.i(TAG, "Proxy started on 127.0.0.1:" + listenPort + " -> " + targetHost + ":" + targetPort);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start proxy: " + e.getMessage());
            return 0;
        }

        return listenPort;
    }

    /**
     * Stop the proxy server.
     */
    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing server socket: " + e.getMessage());
        }
        threadPool.shutdownNow();
        activeStreams.clear();
        Log.i(TAG, "Proxy stopped");
    }

    /**
     * Set foreground/background mode. In background, stream data is buffered.
     */
    public void setForegroundMode(boolean fg) {
        isForeground.set(fg);
        Log.d(TAG, "Foreground mode: " + fg);

        if (fg) {
            // Flush buffered data for all active streams
            for (StreamContext ctx : activeStreams.values()) {
                flushBuffer(ctx);
            }
        }
    }

    /**
     * Get the number of active streaming connections.
     */
    public int getActiveStreamCount() {
        return activeStreams.size();
    }

    /**
     * Get the listening port.
     */
    public int getPort() {
        return listenPort;
    }

    /**
     * Check if the proxy is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    // ===== Internal: Handle a client connection =====

    private void handleClient(Socket clientSocket) {
        try {
            clientSocket.setSoTimeout(30000); // 30s idle timeout
            InputStream clientIn = new BufferedInputStream(clientSocket.getInputStream());
            OutputStream clientOut = new BufferedOutputStream(clientSocket.getOutputStream());

            // Read the HTTP request from client
            byte[] requestBytes = readHttpRequest(clientIn);
            if (requestBytes == null || requestBytes.length == 0) {
                clientSocket.close();
                return;
            }

            // Parse method, path, headers
            HttpRequestInfo reqInfo = parseRequest(requestBytes);
            if (reqInfo == null) {
                clientSocket.close();
                return;
            }

            // Build the target URL
            String targetUrl = targetScheme + "://" + targetHost + ":" + targetPort + reqInfo.path;
            if (targetUrl.contains("?")) {
                // path already contains query string
            } else if (reqInfo.path.contains("?")) {
                // already handled
            }

            // Build OkHttp request
            Request.Builder reqBuilder = new Request.Builder().url(targetUrl);

            // Copy relevant headers (skip hop-by-hop)
            copyHeaders(reqBuilder, reqInfo);

            // Set method and body
            if ("POST".equalsIgnoreCase(reqInfo.method) || "PUT".equalsIgnoreCase(reqInfo.method)
                    || "PATCH".equalsIgnoreCase(reqInfo.method)) {
                okhttp3.MediaType contentType = null;
                String ct = reqInfo.getHeader("content-type");
                if (ct != null) {
                    contentType = okhttp3.MediaType.parse(ct);
                }
                reqBuilder.method(reqInfo.method,
                        okhttp3.RequestBody.create(reqInfo.body, contentType));
            } else {
                reqBuilder.method(reqInfo.method, null);
            }

            Request request = reqBuilder.build();

            // Check if this is a streaming request (SSE)
            boolean isStreaming = isStreamingRequest(reqInfo);

            if (isStreaming) {
                handleStreamingRequest(clientOut, request, reqInfo);
            } else {
                handleRegularRequest(clientOut, request);
            }

            clientSocket.close();
        } catch (Exception e) {
            Log.e(TAG, "Error handling client: " + e.getMessage());
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    private void handleRegularRequest(OutputStream clientOut, Request request) throws IOException {
        try (Response response = httpClient.newCall(request).execute()) {
            // Write HTTP status line
            String statusLine = "HTTP/1.1 " + response.code() + " " + response.message() + "\r\n";
            clientOut.write(statusLine.getBytes());

            // Write response headers
            Headers headers = response.headers();
            for (int i = 0; i < headers.size(); i++) {
                String name = headers.name(i);
                if (isHopByHop(name)) continue;
                clientOut.write((name + ": " + headers.value(i) + "\r\n").getBytes());
            }
            clientOut.write("\r\n".getBytes());

            // Write response body
            ResponseBody body = response.body();
            if (body != null) {
                clientOut.write(body.bytes());
            }
            clientOut.flush();
        }
    }

    private void handleStreamingRequest(OutputStream clientOut, Request request, HttpRequestInfo reqInfo)
            throws IOException {
        String requestId = String.valueOf(System.currentTimeMillis());
        StreamContext ctx = new StreamContext(requestId);
        ctx.clientOut = clientOut;
        activeStreams.put(requestId, ctx);

        try {
            okhttp3.Call call = httpClient.newCall(request);
            Response response = call.execute();
            ResponseBody body = response.body();

            if (body == null) {
                sendError(clientOut, 502, "Empty response from upstream");
                activeStreams.remove(requestId);
                return;
            }

            // Write HTTP status + headers
            String statusLine = "HTTP/1.1 " + response.code() + " " + response.message() + "\r\n";
            clientOut.write(statusLine.getBytes());

            Headers headers = response.headers();
            for (int i = 0; i < headers.size(); i++) {
                String name = headers.name(i);
                if (isHopByHop(name)) continue;
                clientOut.write((name + ": " + headers.value(i) + "\r\n").getBytes());
            }
            clientOut.write("\r\n".getBytes());
            clientOut.flush();

            // Stream the response body
            BufferedSource source = body.source();
            byte[] buf = new byte[4096];
            int bytesRead;

            while ((bytesRead = source.read(buf)) != -1) {
                byte[] chunk = new byte[bytesRead];
                System.arraycopy(buf, 0, chunk, 0, bytesRead);

                if (isForeground.get() && ctx.clientConnected) {
                    try {
                        clientOut.write(chunk);
                        clientOut.flush();
                    } catch (IOException e) {
                        // Client disconnected (WebView went to background)
                        ctx.clientConnected = false;
                        Log.d(TAG, "Client disconnected, buffering stream " + requestId);
                        ctx.buffer.add(chunk);
                        ctx.totalBuffered += bytesRead;
                    }
                } else {
                    // Background mode or client already disconnected: buffer
                    ctx.buffer.add(chunk);
                    ctx.totalBuffered += bytesRead;
                }
            }

            // Stream complete
            if (ctx.clientConnected && isForeground.get()) {
                try {
                    clientOut.flush();
                } catch (IOException ignored) {}
            }

            response.close();
        } catch (IOException e) {
            Log.e(TAG, "Stream error: " + e.getMessage());
            if (ctx.clientConnected && isForeground.get()) {
                try {
                    sendError(clientOut, 502, "Upstream error: " + e.getMessage());
                } catch (IOException ignored) {}
            }
        } finally {
            activeStreams.remove(requestId);
        }
    }

    private void flushBuffer(StreamContext ctx) {
        if (ctx.buffer.isEmpty() || !ctx.clientConnected) return;

        try {
            for (byte[] chunk : ctx.buffer) {
                ctx.clientOut.write(chunk);
            }
            ctx.clientOut.flush();
            ctx.buffer.clear();
            ctx.totalBuffered = 0;
            Log.d(TAG, "Flushed " + ctx.buffer.size() + " buffered chunks for " + ctx.requestId);
        } catch (IOException e) {
            Log.e(TAG, "Error flushing buffer: " + e.getMessage());
        }
    }

    private void sendError(OutputStream out, int code, String message) throws IOException {
        String response = "HTTP/1.1 " + code + " " + message + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + message.length() + "\r\n" +
                "\r\n" + message;
        out.write(response.getBytes());
        out.flush();
    }

    // ===== HTTP parsing helpers =====

    private byte[] readHttpRequest(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];

        // Read headers first: read chunks and look for \r\n\r\n
        int totalRead = 0;
        while (true) {
            int read = in.read(buf, 0, buf.length);
            if (read == -1) break;
            baos.write(buf, 0, read);
            totalRead += read;

            // Check for end of headers in the accumulated data
            String data = baos.toString("ISO-8859-1");
            int headerEnd = data.indexOf("\r\n\r\n");
            if (headerEnd != -1) {
                // Parse Content-Length to read body
                int contentLength = getContentLength(data);
                if (contentLength > 0) {
                    int bodyStart = headerEnd + 4;
                    int bodyBytesRead = totalRead - bodyStart;
                    int remaining = contentLength - bodyBytesRead;
                    while (remaining > 0) {
                        read = in.read(buf, 0, Math.min(buf.length, remaining));
                        if (read == -1) break;
                        baos.write(buf, 0, read);
                        remaining -= read;
                    }
                }
                return baos.toByteArray();
            }
        }
        return baos.toByteArray();
    }

    private int getContentLength(String headerSection) {
        String[] lines = headerSection.split("\r\n");
        for (String line : lines) {
            if (line.toLowerCase().startsWith("content-length:")) {
                try {
                    return Integer.parseInt(line.substring(15).trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private HttpRequestInfo parseRequest(byte[] raw) {
        try {
            String data = new String(raw, "ISO-8859-1");
            String[] parts = data.split("\r\n\r\n", 2);
            String headerSection = parts[0];
            byte[] body = parts.length > 1 ? parts[1].getBytes("ISO-8859-1") : new byte[0];

            String[] lines = headerSection.split("\r\n");
            if (lines.length == 0) return null;

            // Parse request line: METHOD /path HTTP/1.1
            String[] requestLine = lines[0].split(" ", 3);
            if (requestLine.length < 2) return null;

            HttpRequestInfo info = new HttpRequestInfo();
            info.method = requestLine[0];
            info.path = requestLine[1];

            // Parse headers (skip request line)
            for (int i = 1; i < lines.length; i++) {
                int colonIdx = lines[i].indexOf(':');
                if (colonIdx > 0) {
                    String name = lines[i].substring(0, colonIdx).trim().toLowerCase();
                    String value = lines[i].substring(colonIdx + 1).trim();
                    info.headers.put(name, value);
                }
            }

            info.body = body;
            return info;
        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
            return null;
        }
    }

    private void copyHeaders(Request.Builder reqBuilder, HttpRequestInfo reqInfo) {
        for (String name : reqInfo.headers.keySet()) {
            if (isHopByHop(name)) continue;
            if ("host".equals(name)) continue; // We set our own host
            reqBuilder.addHeader(name, reqInfo.headers.get(name));
        }
        // Set the real host
        reqBuilder.addHeader("Host", targetHost + ":" + targetPort);
        reqBuilder.addHeader("X-Forwarded-For", "127.0.0.1");
        reqBuilder.addHeader("X-Forwarded-Proto", targetScheme);
    }

    private boolean isStreamingRequest(HttpRequestInfo reqInfo) {
        String accept = reqInfo.getHeader("accept");
        if (accept != null && accept.contains("text/event-stream")) return true;

        // Check if path looks like a streaming endpoint
        String path = reqInfo.path;
        if (path != null) {
            String[] streamingPaths = {
                "/stream", "/chat/completions", "/completions",
                "/generate", "/api/backends/chat/completions"
            };
            for (String sp : streamingPaths) {
                if (path.contains(sp)) return true;
            }
        }

        // Check body for stream:true
        if (reqInfo.body != null && reqInfo.body.length > 0) {
            try {
                String bodyStr = new String(reqInfo.body, "UTF-8");
                if (bodyStr.contains("\"stream\":true") || bodyStr.contains("\"stream\": true")) {
                    return true;
                }
            } catch (Exception ignored) {}
        }

        return false;
    }

    private boolean isHopByHop(String headerName) {
        switch (headerName.toLowerCase()) {
            case "connection":
            case "keep-alive":
            case "proxy-authenticate":
            case "proxy-authorization":
            case "te":
            case "trailer":
            case "transfer-encoding":
            case "upgrade":
                return true;
            default:
                return false;
        }
    }

    /**
     * Simple HTTP request info container.
     */
    private static class HttpRequestInfo {
        String method;
        String path;
        byte[] body;
        java.util.Map<String, String> headers = new java.util.HashMap<>();

        String getHeader(String name) {
            return headers.get(name.toLowerCase());
        }
    }
}
