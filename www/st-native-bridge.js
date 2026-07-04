/**
 * SillyTavern Native Streaming Bridge
 * Injected into WebView to intercept streaming requests and route them through
 * the native StreamingService for background resilience.
 */
(function() {
    'use strict';

    // Skip if already injected
    if (window.__st_bridge_injected) return;
    window.__st_bridge_injected = true;

    // Track active native streams
    var activeStreams = {};

    // Check if native bridge is available
    function hasNativeBridge() {
        return !!(window.StreamingBridge && window.StreamingBridge.isNativeStreamingSupported &&
                  window.StreamingBridge.isNativeStreamingSupported());
    }

    // Detect if a request is likely a streaming AI completion request
    function isStreamingRequest(url, body, headers) {
        // Check URL patterns
        var streamingPaths = [
            '/stream',
            '/chat/completions',
            '/completions',
            '/generate',
            '/api/backends/chat/completions',
            '/api/backends/stream',
            '/api/plugins/sd/',
            '/api/sd/'
        ];
        for (var i = 0; i < streamingPaths.length; i++) {
            if (url.indexOf(streamingPaths[i]) !== -1) return true;
        }

        // Check body for stream:true
        if (body) {
            try {
                var parsed = typeof body === 'string' ? JSON.parse(body) : body;
                if (parsed.stream === true) return true;
            } catch(e) {}
        }

        // Check if content-type is SSE
        if (headers && headers['Content-Type'] && headers['Content-Type'].indexOf('text/event-stream') !== -1) return true;
        if (headers && headers['content-type'] && headers['content-type'].indexOf('text/event-stream') !== -1) return true;

        return false;
    }

    // ========== Intercept EventSource (SSE) ==========
    var OriginalEventSource = window.EventSource;

    function NativeEventSource(url, options) {
        var self = this;
        this.url = url;
        this.readyState = 0; // CONNECTING
        this.onopen = null;
        this.onmessage = null;
        this.onerror = null;
        this._eventListeners = {};
        this._requestId = null;

        if (!hasNativeBridge()) {
            // Fallback to original EventSource
            var fallback = new OriginalEventSource(url, options);
            fallback.onopen = function(e) { self.readyState = 1; if (self.onopen) self.onopen.call(self, e); };
            fallback.onmessage = function(e) { if (self.onmessage) self.onmessage.call(self, e); };
            fallback.onerror = function(e) { self.readyState = 2; if (self.onerror) self.onerror.call(self, e); };
            this._fallback = fallback;
            return;
        }

        // Route through native bridge
        var headers = {};
        if (options && options.headers) {
            for (var key in options.headers) {
                if (options.headers.hasOwnProperty(key)) {
                    headers[key] = options.headers[key];
                }
            }
        }

        var requestId = window.StreamingBridge.streamFetch(url, 'GET', JSON.stringify(headers), '');
        this._requestId = requestId;
        activeStreams[requestId] = this;
        this.readyState = 0;
    }

    NativeEventSource.prototype = {
        addEventListener: function(type, listener) {
            if (this._fallback) {
                this._fallback.addEventListener(type, listener);
                return;
            }
            if (!this._eventListeners[type]) this._eventListeners[type] = [];
            this._eventListeners[type].push(listener);
        },
        removeEventListener: function(type, listener) {
            if (this._fallback) {
                this._fallback.removeEventListener(type, listener);
                return;
            }
            var list = this._eventListeners[type];
            if (list) {
                var idx = list.indexOf(listener);
                if (idx !== -1) list.splice(idx, 1);
            }
        },
        _dispatchEvent: function(type, data) {
            this.readyState = 1; // OPEN
            var handlers = this._eventListeners[type] || [];
            var event = { type: type, data: data, target: this };

            if (type === 'message' && this.onmessage) this.onmessage.call(this, event);
            for (var i = 0; i < handlers.length; i++) {
                try { handlers[i].call(this, event); } catch(e) {}
            }
        },
        _dispatchError: function(error) {
            this.readyState = 2; // CLOSED
            var handlers = this._eventListeners['error'] || [];
            var event = { type: 'error', data: error, target: this };
            if (this.onerror) this.onerror.call(this, event);
            for (var i = 0; i < handlers.length; i++) {
                try { handlers[i].call(this, event); } catch(e) {}
            }
        },
        close: function() {
            if (this._fallback) {
                this._fallback.close();
                return;
            }
            this.readyState = 2;
            if (this._requestId && window.StreamingBridge) {
                window.StreamingBridge.cancelStream(this._requestId);
            }
            delete activeStreams[this._requestId];
        }
    };

    // ========== Intercept fetch() ==========
    var originalFetch = window.fetch;

    window.fetch = function(url, options) {
        options = options || {};
        var method = options.method || 'GET';
        var headers = options.headers || {};
        var body = options.body || null;

        // Check if this is a streaming request
        if (hasNativeBridge() && isStreamingRequest(url.toString(), body, headers)) {
            // Convert headers to plain object for JSON serialization
            var headersObj = {};
            if (headers && typeof headers.forEach === 'function') {
                // Headers object
                headers.forEach(function(value, key) {
                    headersObj[key] = value;
                });
            } else if (typeof headers === 'object') {
                // Plain object - already in the right format
                for (var key in headers) {
                    if (headers.hasOwnProperty(key)) {
                        headersObj[key] = headers[key];
                    }
                }
            }

            // Route through native bridge
            var bodyStr = '';
            if (typeof body === 'string') {
                bodyStr = body;
            } else if (body && typeof body === 'object') {
                try { bodyStr = JSON.stringify(body); } catch(e) { bodyStr = ''; }
            }

            var requestId = window.StreamingBridge.streamFetch(
                url.toString(),
                method,
                JSON.stringify(headersObj),
                bodyStr
            );

            // Return a promise that resolves with a simulated Response
            return new Promise(function(resolve, reject) {
                var chunks = [];
                var nativeStream = {
                    _requestId: requestId,
                    onData: function(data) {
                        chunks.push(data);
                    },
                    onDone: function() {
                        var fullText = chunks.join('\n');
                        var response = createStreamResponse(fullText, url.toString());
                        resolve(response);
                    },
                    onError: function(error) {
                        reject(new Error(error));
                    }
                };
                activeStreams[requestId] = nativeStream;
            });
        }

        // Fall through to original fetch
        return originalFetch.call(this, url, options);
    };

    // Helper: create a Response-like object from the streamed text
    function createStreamResponse(text, url) {
        var body = new ReadableStream({
            start: function(controller) {
                var lines = text.split('\n');
                for (var i = 0; i < lines.length; i++) {
                    controller.enqueue(new TextEncoder().encode(lines[i] + '\n'));
                }
                controller.close();
            }
        });

        return {
            ok: true,
            status: 200,
            url: url,
            headers: new Map([['content-type', 'text/event-stream']]),
            body: body,
            text: function() { return Promise.resolve(text); },
            json: function() {
                try { return Promise.resolve(JSON.parse(text)); }
                catch(e) { return Promise.reject(e); }
            }
        };
    }

    // Replace EventSource
    window.EventSource = NativeEventSource;
    window.NativeEventSource = NativeEventSource;
    window.OriginalEventSource = OriginalEventSource;

    // ========== Global callbacks called by native StreamingBridge ==========

    /**
     * Called by native side when streaming data arrives.
     * Dispatches to the appropriate active stream handler.
     */
    window.__st_bridge_onData = function(requestId, chunk) {
        var stream = activeStreams[requestId];
        if (!stream) return;

        if (stream._dispatchEvent) {
            // EventSource-style
            stream._dispatchEvent('message', chunk);
        } else if (stream.onData) {
            // fetch-style
            stream.onData(chunk);
        }
    };

    /**
     * Called by native side when a batch of buffered data is flushed
     * (after returning from background).
     */
    window.__st_bridge_onBatch = function(requestId, batchText) {
        var stream = activeStreams[requestId];
        if (!stream) return;

        var lines = batchText.split('\n');
        for (var i = 0; i < lines.length; i++) {
            if (!lines[i]) continue;
            if (stream._dispatchEvent) {
                stream._dispatchEvent('message', lines[i]);
            } else if (stream.onData) {
                stream.onData(lines[i]);
            }
        }
    };

    /**
     * Called by native side when streaming completes.
     */
    window.__st_bridge_onDone = function(requestId) {
        var stream = activeStreams[requestId];
        if (!stream) return;
        delete activeStreams[requestId];

        if (stream.onDone) {
            stream.onDone();
        }
        // For EventSource, the close event is natural end
    };

    /**
     * Called by native side when streaming errors.
     */
    window.__st_bridge_onError = function(requestId, error) {
        var stream = activeStreams[requestId];
        if (!stream) return;
        delete activeStreams[requestId];

        if (stream._dispatchError) {
            stream._dispatchError(error);
        } else if (stream.onError) {
            stream.onError(error);
        }
    };

    console.log('[ST-NativeBridge] Injection complete. Native streaming: ' + hasNativeBridge());
})();
