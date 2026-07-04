/**
 * SillyTavern Native Bridge
 * Minimal script - only registers native-to-JS callbacks.
 * No API interception. WebView streaming continues normally.
 */
(function() {
    'use strict';
    if (window.__st_bridge_injected) return;
    window.__st_bridge_injected = true;

    var activeStreams = {};

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
        if (stream.onDone) try { stream.onDone(); } catch(e) {}
        if (stream._emit) stream._emit('done');
    };

    window.__st_bridge_onError = function(requestId, error) {
        var stream = activeStreams[requestId];
        if (!stream) return;
        delete activeStreams[requestId];
        if (stream.onError) try { stream.onError(error); } catch(e) {}
        if (stream._emit) stream._emit('error', error);
    };

    console.log('[ST-Bridge] Ready');
})();
