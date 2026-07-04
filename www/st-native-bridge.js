/**
 * SillyTavern Native Streaming Bridge
 * This script is injected at document start to provide native streaming support.
 * It only registers callbacks - no API interception until explicitly enabled.
 */
(function() {
    'use strict';

    if (window.__st_bridge_injected) return;
    window.__st_bridge_injected = true;

    // Track active native streams
    var activeStreams = {};

    // Check if native bridge is available
    function hasNativeBridge() {
        try {
            return !!(window.StreamingBridge &&
                      window.StreamingBridge.isNativeStreamingSupported &&
                      window.StreamingBridge.isNativeStreamingSupported());
        } catch(e) {
            return false;
        }
    }

    /**
     * Called by native side when streaming data arrives.
     */
    window.__st_bridge_onData = function(requestId, chunk) {
        var stream = activeStreams[requestId];
        if (!stream) return;

        if (stream._emit) {
            stream._emit('data', chunk);
        } else if (stream.onData) {
            try { stream.onData(chunk); } catch(e) {}
        }
    };

    /**
     * Called by native side when a batch of buffered data is flushed.
     */
    window.__st_bridge_onBatch = function(requestId, batchText) {
        var stream = activeStreams[requestId];
        if (!stream) return;

        var lines = batchText.split('\n');
        for (var i = 0; i < lines.length; i++) {
            if (!lines[i]) continue;
            if (stream._emit) {
                stream._emit('data', lines[i]);
            } else if (stream.onData) {
                try { stream.onData(lines[i]); } catch(e) {}
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
            try { stream.onDone(); } catch(e) {}
        }
        if (stream._emit) {
            stream._emit('done');
        }
    };

    /**
     * Called by native side when streaming errors.
     */
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

    // Public API for creating a native-backed stream
    window.__st_createStream = function(url, options) {
        if (!hasNativeBridge()) return null;

        options = options || {};
        var headers = options.headers || {};

        var headersObj = {};
        for (var key in headers) {
            if (headers.hasOwnProperty(key)) {
                headersObj[key] = headers[key];
            }
        }

        var bodyStr = '';
        if (options.body) {
            if (typeof options.body === 'string') {
                bodyStr = options.body;
            } else if (typeof options.body === 'object') {
                try { bodyStr = JSON.stringify(options.body); } catch(e) {}
            }
        }

        var method = options.method || 'GET';
        var requestId = window.StreamingBridge.streamFetch(
            url, method, JSON.stringify(headersObj), bodyStr
        );

        var stream = {
            requestId: requestId,
            onData: null,
            onDone: null,
            onError: null,
            _listeners: {},
            cancel: function() {
                if (window.StreamingBridge) {
                    window.StreamingBridge.cancelStream(requestId);
                }
                delete activeStreams[requestId];
            },
            on: function(event, callback) {
                if (!this._listeners[event]) this._listeners[event] = [];
                this._listeners[event].push(callback);
                return this;
            },
            _emit: function(event, data) {
                var listeners = this._listeners[event] || [];
                for (var i = 0; i < listeners.length; i++) {
                    try { listeners[i](data); } catch(e) {}
                }
            }
        };

        activeStreams[requestId] = stream;
        return stream;
    };

    console.log('[ST-NativeBridge] Ready. Native streaming: ' + hasNativeBridge());
})();
