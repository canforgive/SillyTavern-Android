/**
 * SillyTavern Native Streaming Bridge
 * Phase 1: Register callbacks at document start (no API interception)
 * Phase 2: Apply EventSource/fetch overrides after page initialization
 */
(function() {
    'use strict';

    if (window.__st_bridge_injected) return;
    window.__st_bridge_injected = true;

    // ========== Phase 1: Infrastructure (runs at document start) ==========

    var activeStreams = {};

    function hasNativeBridge() {
        try {
            return !!(window.StreamingBridge &&
                      window.StreamingBridge.isNativeStreamingSupported &&
                      window.StreamingBridge.isNativeStreamingSupported());
        } catch(e) {
            return false;
        }
    }

    // Global callbacks called by native StreamingBridge
    window.__st_bridge_onData = function(requestId, chunk) {
        var stream = activeStreams[requestId];
        if (!stream) return;
        if (stream._emit) {
            stream._emit('message', chunk);
        } else if (stream.onData) {
            try { stream.onData(chunk); } catch(e) {}
        }
    };

    window.__st_bridge_onBatch = function(requestId, batchText) {
        var stream = activeStreams[requestId];
        if (!stream) return;
        var lines = batchText.split('\n');
        for (var i = 0; i < lines.length; i++) {
            if (!lines[i]) continue;
            if (stream._emit) {
                stream._emit('message', lines[i]);
            } else if (stream.onData) {
                try { stream.onData(lines[i]); } catch(e) {}
            }
        }
    };

    window.__st_bridge_onDone = function(requestId) {
        var stream = activeStreams[requestId];
        if (!stream) return;
        delete activeStreams[requestId];
        if (stream.onDone) {
            try { stream.onDone(); } catch(e) {}
        }
        if (stream._emit) {
            stream._emit('done');
        }
    };

    window.__st_bridge_onError = function(requestId, error) {
        var stream = activeStreams[requestId];
        if (!stream) return;
        delete activeStreams[requestId];
        if (stream.onError) {
            try { stream.onError(error); } catch(e) {}
        }
        if (stream._emit) {
            stream._emit('error', error);
        }
    };

    // ========== Phase 2: Apply API overrides after page init ==========

    function isStreamingRequest(url, body) {
        var urlStr = url.toString();
        var streamingPaths = [
            '/stream', '/chat/completions', '/completions',
            '/generate', '/api/backends/chat/completions'
        ];
        for (var i = 0; i < streamingPaths.length; i++) {
            if (urlStr.indexOf(streamingPaths[i]) !== -1) return true;
        }
        if (body) {
            try {
                var parsed = typeof body === 'string' ? JSON.parse(body) : body;
                if (parsed && parsed.stream === true) return true;
            } catch(e) {}
        }
        return false;
    }

    function overrideEventSource() {
        if (!hasNativeBridge()) return;
        if (window.__st_eventSource_overridden) return;
        window.__st_eventSource_overridden = true;

        var OriginalEventSource = window.EventSource;

        function NativeEventSource(url, options) {
            var self = this;
            this.url = url;
            this.readyState = 0;
            this.onopen = null;
            this.onmessage = null;
            this.onerror = null;
            this._listeners = {};
            this._requestId = null;
            this._closed = false;

            try {
                var headers = {};
                if (options && options.headers) {
                    for (var key in options.headers) {
                        if (options.headers.hasOwnProperty(key)) {
                            headers[key] = options.headers[key];
                        }
                    }
                }

                this._requestId = window.StreamingBridge.streamFetch(
                    url, 'GET', JSON.stringify(headers), ''
                );
                activeStreams[this._requestId] = this;
            } catch(e) {
                // Fallback to original EventSource on error
                var fb = new OriginalEventSource(url, options);
                this._fallback = fb;
                this.readyState = fb.readyState;
                return;
            }
        }

        NativeEventSource.prototype = {
            addEventListener: function(type, listener) {
                if (this._fallback) { this._fallback.addEventListener(type, listener); return; }
                if (!this._listeners[type]) this._listeners[type] = [];
                this._listeners[type].push(listener);
            },
            removeEventListener: function(type, listener) {
                if (this._fallback) { this._fallback.removeEventListener(type, listener); return; }
                var list = this._listeners[type];
                if (list) {
                    var idx = list.indexOf(listener);
                    if (idx !== -1) list.splice(idx, 1);
                }
            },
            _emit: function(type, data) {
                if (this._closed) return;
                this.readyState = 1;
                var event = { type: type, data: data, target: this };
                if (type === 'message' && this.onmessage) {
                    try { this.onmessage.call(this, event); } catch(e) {}
                }
                if (type === 'error' && this.onerror) {
                    try { this.onerror.call(this, event); } catch(e) {}
                }
                var handlers = this._listeners[type] || [];
                for (var i = 0; i < handlers.length; i++) {
                    try { handlers[i].call(this, event); } catch(e) {}
                }
            },
            close: function() {
                this._closed = true;
                this.readyState = 2;
                if (this._fallback) { this._fallback.close(); return; }
                if (this._requestId && window.StreamingBridge) {
                    try { window.StreamingBridge.cancelStream(this._requestId); } catch(e) {}
                }
                delete activeStreams[this._requestId];
            }
        };

        window.EventSource = NativeEventSource;
        console.log('[ST-Bridge] EventSource overridden');
    }

    function overrideFetch() {
        if (!hasNativeBridge()) return;
        if (window.__st_fetch_overridden) return;
        window.__st_fetch_overridden = true;

        var originalFetch = window.fetch;

        window.fetch = function(url, options) {
            options = options || {};
            var body = options.body || null;

            if (isStreamingRequest(url.toString(), body)) {
                var method = options.method || 'POST';
                var headersObj = {};

                try {
                    var headers = options.headers || {};
                    if (headers && typeof headers.forEach === 'function') {
                        headers.forEach(function(value, key) {
                            headersObj[key] = value;
                        });
                    } else if (typeof headers === 'object') {
                        for (var key in headers) {
                            if (headers.hasOwnProperty(key)) {
                                headersObj[key] = headers[key];
                            }
                        }
                    }
                } catch(e) {}

                var bodyStr = '';
                if (typeof body === 'string') {
                    bodyStr = body;
                } else if (body && typeof body === 'object') {
                    try { bodyStr = JSON.stringify(body); } catch(e) {}
                }

                try {
                    var requestId = window.StreamingBridge.streamFetch(
                        url.toString(), method, JSON.stringify(headersObj), bodyStr
                    );

                    return new Promise(function(resolve, reject) {
                        var chunks = [];
                        var stream = {
                            onData: function(data) { chunks.push(data); },
                            onDone: function() {
                                var text = chunks.join('\n');
                                resolve({
                                    ok: true, status: 200,
                                    text: function() { return Promise.resolve(text); },
                                    json: function() {
                                        try { return Promise.resolve(JSON.parse(text)); }
                                        catch(e) { return Promise.reject(e); }
                                    }
                                });
                            },
                            onError: function(err) {
                                reject(new Error(err));
                            }
                        };
                        activeStreams[requestId] = stream;
                    });
                } catch(e) {
                    // Fallback to original fetch
                    return originalFetch.call(this, url, options);
                }
            }

            return originalFetch.call(this, url, options);
        };

        console.log('[ST-Bridge] fetch overridden');
    }

    function applyOverrides() {
        if (!hasNativeBridge()) {
            console.log('[ST-Bridge] Native bridge not available, skipping overrides');
            return;
        }
        overrideEventSource();
        overrideFetch();
    }

    // Apply overrides after page has initialized (DOM ready + delay)
    if (document.readyState === 'complete' || document.readyState === 'interactive') {
        // Page already loaded, apply with a small delay
        setTimeout(applyOverrides, 500);
    } else {
        // Wait for DOMContentLoaded then apply with delay
        document.addEventListener('DOMContentLoaded', function() {
            setTimeout(applyOverrides, 1000);
        });
    }

    // Also listen for window.load as a fallback
    window.addEventListener('load', function() {
        // Ensure overrides are applied even if DOMContentLoaded didn't fire
        setTimeout(applyOverrides, 500);
    });

    console.log('[ST-Bridge] Phase 1 complete, overrides will apply after page init');
})();
