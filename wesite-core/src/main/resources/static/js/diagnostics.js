(function () {
    'use strict';
    var config = window.__diagnostics;
    if (!config || !config.enabled || window.__diagnosticsInstalled || !window.fetch) return;
    window.__diagnosticsInstalled = true;
    var originalFetch = window.fetch;
    var queue = [], timer = null, sent = 0, windowStart = Date.now();
    var observed = new WeakSet();
    var methods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'];
    var routes = (config.routes || []).filter(function (r) { return r !== 'unmatched'; }).map(function (r) {
        var expression = r.split('/').map(function (part) {
            return part.indexOf('{') === 0 ? '[^/]+' : part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        }).join('/');
        return {name: r, pattern: new RegExp('^' + expression + '/?$'), dynamic: r.indexOf('{') !== -1};
    }).sort(function (a, b) { return Number(a.dynamic) - Number(b.dynamic); });
    function url(value) {
        try { return new URL(value, window.location.href); } catch (_) { return null; }
    }
    function own(value) {
        var parsed = url(value);
        return parsed && parsed.origin === window.location.origin && parsed.pathname.indexOf('/diagnostics/') !== 0 ? parsed : null;
    }
    function route(path) {
        for (var i = 0; i < routes.length; i++) if (routes[i].pattern.test(path)) return routes[i].name;
        return 'unmatched';
    }
    function uuid() {
        if (window.crypto && window.crypto.randomUUID) return window.crypto.randomUUID();
        if (!window.crypto || !window.crypto.getRandomValues) return null;
        var bytes = new Uint8Array(16); window.crypto.getRandomValues(bytes);
        bytes[6] = bytes[6] & 15 | 64; bytes[8] = bytes[8] & 63 | 128;
        var hex = Array.from(bytes, function (b) { return b.toString(16).padStart(2, '0'); }).join('');
        return hex.slice(0,8)+'-'+hex.slice(8,12)+'-'+hex.slice(12,16)+'-'+hex.slice(16,20)+'-'+hex.slice(20);
    }
    function enqueue(event) {
        try {
            if (Date.now() - windowStart >= 60000) { windowStart = Date.now(); sent = 0; }
            if (sent >= 60 || queue.length >= 20) return;
            event.id = uuid(); if (!event.id) return;
            sent++; queue.push(event);
            if (!timer) timer = setTimeout(flush, 1000);
        } catch (_) { /* Diagnostics never change application behavior. */ }
    }
    function flush() {
        timer = null;
        if (!queue.length) return;
        var batch = queue.splice(0, 10), controller = new AbortController();
        var timeout = setTimeout(function () { controller.abort(); }, 5000);
        try {
            Promise.resolve(originalFetch.call(window, '/diagnostics/events', {
                method: 'POST', credentials: 'same-origin', headers: {'Content-Type':'application/json'},
                body: JSON.stringify({events:batch}), signal:controller.signal
            })).catch(function () {}).finally(function () { clearTimeout(timeout); });
        } catch (_) { clearTimeout(timeout); }
        if (queue.length) timer = setTimeout(flush, 1000);
    }
    function failure(parsed, method, status, requestId) {
        if (!parsed) return;
        var event = {kind:'request_failure', route:route(parsed.pathname), method:methods.indexOf(method)>=0?method:'OTHER',
            code:status >= 500?'HTTP_5XX':'NETWORK_FAILURE', status:status};
        if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requestId || '')) event.requestId = requestId;
        enqueue(event);
    }
    window.fetch = function (input, init) {
        var parsed, method;
        try { parsed = own(typeof input === 'string' || input instanceof URL ? input : input.url); method = String(init && init.method || input && input.method || 'GET').toUpperCase(); } catch (_) {}
        return originalFetch.apply(this, arguments).then(function (response) {
            try { if (response.status >= 500) failure(parsed, method, response.status, response.headers.get('X-Request-ID')); } catch (_) {}
            return response;
        }, function (error) {
            try {
                if (error && typeof error === 'object') observed.add(error);
                if (!error || error.name !== 'AbortError') failure(parsed, method, 0, null);
            } catch (_) {}
            throw error;
        });
    };
    if (window.XMLHttpRequest) {
        var open = XMLHttpRequest.prototype.open, send = XMLHttpRequest.prototype.send;
        var requests = new WeakMap();
        XMLHttpRequest.prototype.open = function (method, target) {
            var result = open.apply(this, arguments);
            requests.set(this, {parsed:own(target), method:String(method).toUpperCase()});
            return result;
        };
        XMLHttpRequest.prototype.send = function () {
            var xhr = this, request = requests.get(xhr), done = false;
            function report(status) {
                if (done || !request) return; done = true;
                try { failure(request.parsed, request.method, status, status ? xhr.getResponseHeader('X-Request-ID') : null); } catch (_) {}
            }
            function load() { if (xhr.status >= 500) report(xhr.status); }
            function failed() { report(0); }
            function cleanup() {
                xhr.removeEventListener('load',load); xhr.removeEventListener('error',failed);
                xhr.removeEventListener('timeout',failed); xhr.removeEventListener('loadend',cleanup);
            }
            xhr.addEventListener('load',load); xhr.addEventListener('error',failed);
            xhr.addEventListener('timeout',failed); xhr.addEventListener('loadend',cleanup);
            try { return send.apply(this,arguments); } catch (error) { cleanup(); throw error; }
        };
    }
    function scriptLocation(filename) {
        var parsed = own(filename); if (!parsed) return null;
        if ((config.scripts || []).indexOf(parsed.pathname) >= 0) return parsed.pathname;
        if (parsed.pathname === window.location.pathname && route(parsed.pathname) !== 'unmatched') return 'inline';
        return null;
    }
    function capture(error, filename, line, column, fallback) {
        try {
            if (error && (observed.has(error) || error.name === 'AbortError')) return;
            var script = filename && scriptLocation(filename);
            if (!script && error && typeof error.stack === 'string') {
                var matches = error.stack.match(/https?:\/\/[^\s)]+:\d+:\d+/g) || [];
                for (var i=0; i<matches.length; i++) {
                    var parts = /^(.*):(\d+):(\d+)$/.exec(matches[i]);
                    script = scriptLocation(parts[1]);
                    if (script) { line=Number(parts[2]); column=Number(parts[3]); break; }
                }
            }
            if (!script) return;
            var code = error && error.name;
            if (['Error','TypeError','ReferenceError','SyntaxError','RangeError','URIError','EvalError','AggregateError'].indexOf(code)<0) code=fallback;
            if (error && typeof error === 'object') observed.add(error);
            enqueue({kind:'client_error',code:code,route:route(window.location.pathname),script:script,
                line:Math.max(0,Math.min(1000000,Number(line)||0)),column:Math.max(0,Math.min(1000000,Number(column)||0))});
        } catch (_) {}
    }
    window.addEventListener('error',function (e) { capture(e.error,e.filename,e.lineno,e.colno,'Error'); });
    window.addEventListener('unhandledrejection',function (e) { capture(e.reason,null,0,0,'UNHANDLED_REJECTION'); });
})();
