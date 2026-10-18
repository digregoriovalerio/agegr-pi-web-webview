(function() {
  if (window.__piBridgeInstalled) return;
  window.__piBridgeInstalled = true;
  if (window.javaBridge) javaBridge.log("[bridge] script executing");

  // --- Console capture ---
  window.onerror = function(msg, url, line) {
    if (window.javaBridge) javaBridge.error(String(msg) + ' at ' + url + ':' + line);
    return false;
  };
  var origLog = console.log.bind(console);
  var origError = console.error.bind(console);
  console.log = function() { origLog.apply(console, arguments); if (window.javaBridge) javaBridge.log(Array.prototype.join.call(arguments, ' ')); };
  console.error = function() { origError.apply(console, arguments); if (window.javaBridge) javaBridge.error(Array.prototype.join.call(arguments, ' ')); };

  // --- Global SSE state object ---
  window.__piSSE = {
    _closed: false,
    _buffer: '',
    _pending: [],
    _activeUrl: null,
    onmessage: null,
    onerror: null,
    url: '',
    readyState: 0,
    _listeners: [],
    _maxPendingSize: 100,  // Prevent unbounded growth
    flush: function() {
      if (this._pending.length === 0) return;
      var events = this._pending.splice(0, this._pending.length);
      for (var i = 0; i < events.length; i++) {
        var evt = events[i];
        if (this.onmessage) { try { this.onmessage(evt); } catch(e) {} }
        for (var j = 0; j < this._listeners.length; j++) {
          if (this._listeners[j].type === evt.type) {
            try { this._listeners[j].handler(evt); } catch(e2) {}
          }
        }
      }
    },
    addEventListener: function(type, handler) {
      if (type === 'message' || type === 'open' || type === 'error') {
        this._listeners.push({type: type, handler: handler});
        this.flush();
      }
    },
    removeEventListener: function(type, handler) {
      if (type === 'message' || type === 'open' || type === 'error') {
        for (var i = 0; i < this._listeners.length; i++) {
          if (this._listeners[i].handler === handler) {
            this._listeners.splice(i, 1);
            break;
          }
        }
      }
    },
    processBuffer: function() {
      var NL = String.fromCharCode(10);
      var lines = this._buffer.split(NL);
      this._buffer = lines.pop();
      for (var i = 0; i < lines.length; i++) {
        var line = lines[i];
        var eventType = 'message';
        var payload = '';
        if (line.startsWith("event: ")) {
          eventType = line.substring(7).trim();
        } else if (line.startsWith("data: ")) {
          payload = line.substring(6);
        } else if (line.startsWith("data:")) {
          payload = line.substring(5);
        } else if (line.startsWith("id: ")) {
          // Last-Event-ID - could be used for reconnection
          this._lastEventId = line.substring(4).trim();
        } else if (line.startsWith("retry: ")) {
          // Reconnection delay - could be used for reconnection
          this._retry = parseInt(line.substring(7).trim(), 10);
        }
        if (payload !== '') {
          var evt = new MessageEvent(eventType, {data: payload});
          this._pending.push(evt);
          // Prevent unbounded queue growth
          if (this._pending.length > this._maxPendingSize) {
            this._pending.shift(); // Drop oldest event
          }
        }
      }
      this.flush();
    }
  };

  // --- Receive messages from Java ---
  window.receiveMessageFromJava = function(msg) {
    try {
      if (window.javaBridge) javaBridge.log("[bridge] [receiveMessageFromJava] got " + (msg ? msg.substring(0, Math.min(80, msg.length)) + "..." : 'null'));
    } catch(e) {}
    if (window.__piSSE && !window.__piSSE._closed) {
      window.__piSSE._buffer += msg;
      window.__piSSE.processBuffer();
    } else {
      try {
        if (window.javaBridge) javaBridge.log("[bridge] [receiveMessageFromJava] __piSSE not ready or closed - event LOST");
      } catch(e) {}
    }
  };

  // --- EventSource polyfill for JavaFX WebView ---
  (function installEventSourcePolyfill() {
    if (window.__piEventSourcePatched) return;
    window.__piEventSourcePatched = true;

    var NativeEventSource = window.EventSource;

    window.EventSource = function(url, options) {
      this.url = url;
      this.readyState = 0;
      this.onopen = null;
      this.onmessage = null;
      this.onerror = null;
      this._closed = false;
      this._reconnectTimer = null;
      this._withCredentials = options && options.withCredentials === true;
      this._lastEventId = null;

      var bridged = false;
      if (window.javaBridge) {
        try {
          window.javaBridge.postMessage("__PI_SSE_START__:" + url);
          bridged = true;
        } catch (e) {
          // javaBridge exists but is broken (e.g. GC'd bridge object) — fall back
          try { window.javaBridge.log("[bridge] [EventSource] postMessage failed, falling back to native EventSource: " + e); } catch (e2) {}
          bridged = false;
        }
      }
      if (!bridged) {
        return new NativeEventSource(url, options);
      }

      this.readyState = 1;
      if (this.onopen) this.onopen(new Event("open"));

      window.__piSSE._closed = false;
      window.__piSSE._buffer = '';
      window.__piSSE._activeUrl = url;
      window.__piSSE.url = url;
      window.__piSSE.readyState = 1;
      if (options && options.onmessage) { window.__piSSE.onmessage = options.onmessage; }
      if (options && options.onerror) { window.__piSSE.onerror = options.onerror; }
    };

    window.EventSource.CONNECTING = 0;
    window.EventSource.OPEN = 1;
    window.EventSource.CLOSED = 2;

    window.EventSource.prototype.close = function() {
      this._closed = true;
      this.readyState = 2;
      if (this._reconnectTimer) {
        clearTimeout(this._reconnectTimer);
        this._reconnectTimer = null;
      }
      if (window.javaBridge) {
        try { window.javaBridge.postMessage("__PI_SSE_STOP__:" + this.url); } catch(e) {}
      }
      if (window.__piSSE) {
        window.__piSSE._closed = true;
        window.__piSSE.onmessage = null;
        window.__piSSE.onerror = null;
        window.__piSSE._listeners = [];
      }
    };

    window.EventSource.prototype._scheduleReconnect = function() {
      var self = this;
      if (self._closed) return;
      var delay = self._retry || 2000; // Default 2s, can be overridden by server
      self._reconnectTimer = setTimeout(function() {
        if (!self._closed) {
          try {
            window.javaBridge.postMessage("__PI_SSE_START__:" + self.url + (self._lastEventId ? ("?lastEventId=" + self._lastEventId) : ""));
          } catch(e) {}
        }
      }, delay);
    };

    var origProto = window.EventSource.prototype;
    Object.defineProperty(origProto, 'onmessage', {
      get: function() { return window.__piSSE ? window.__piSSE.onmessage : null; },
      set: function(fn) {
        if (window.__piSSE) {
          window.__piSSE.onmessage = fn;
          try { if (window.javaBridge) javaBridge.log("[bridge] [EventSource.onmessage.set] handler=" + (typeof fn)); } catch(e) {}
        }
      },
      configurable: true
    });
    Object.defineProperty(origProto, 'onerror', {
      get: function() { return window.__piSSE ? window.__piSSE.onerror : null; },
      set: function(fn) { if (window.__piSSE) window.__piSSE.onerror = fn; },
      configurable: true
    });
    Object.defineProperty(origProto, 'onopen', {
      get: function() { return this._onopen; },
      set: function(fn) { this._onopen = fn; },
      configurable: true
    });
  })();
})();