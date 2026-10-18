(function autoHello() {
  // ===========================================================================
  // autoHello.js — Dev-only: auto-types "Hello" and submits on page load
  // ===========================================================================
  //
  // PURPOSE:
  //   Smoke-tests the end-to-end Hello → reply flow without manual user
  //   interaction. On page load it:
  //     1. Finds the chat input (tries multiple selectors in order)
  //     2. Sets its value to "Hello" (React-compatible setter + input event)
  //     3. Dispatches Enter keydown/keypress/keyup to submit
  //     4. Verifies the textarea was cleared (indicating successful submit)
  //
  // LIFECYCLE:
  //   Guarded by window.__piAutoHelloDone — runs once per page load.
  //   Retries finding the input up to 30 times at 500ms intervals to allow
  //   React hydration time.
  //
  // PRODUCTION:
  //   Set AppConfig.AUTOHELLO_ENABLED = false to remove AutoHello entirely.
  //   Not needed for manual use — the user types and submits through the
  //   page's own UI.
  //
  // LOGGING:
  //   All actions are logged via javaBridge.log("[autoHello] ..."). If the
  //   JSBridge has been garbage-collected (see ProxiedWebView), these logs
  //   are silently swallowed — which is why the original "no reply" bug was
  //   so hard to diagnose.
  // ===========================================================================

  if (window.__piAutoHelloDone) return;
  window.__piAutoHelloDone = true;
  if (window.javaBridge) javaBridge.log("[autoHello] IIFE started");

  // Track timeouts for cleanup on navigation/unload
  var pendingTimeouts = [];
  function setTimeoutTracked(fn, delay) {
    var id = setTimeout(fn, delay);
    pendingTimeouts.push(id);
    return id;
  }
  function clearAllTimeouts() {
    for (var i = 0; i < pendingTimeouts.length; i++) {
      clearTimeout(pendingTimeouts[i]);
    }
    pendingTimeouts = [];
  }
  window.addEventListener('beforeunload', clearAllTimeouts);
  window.addEventListener('pagehide', clearAllTimeouts);

  function log(msg) {
    try { if (window.javaBridge) javaBridge.log("[autoHello] " + msg); } catch(e) {}
  }

  // Sets an input value using React-compatible native setter, then dispatches
  // an input event so React's controlled component picks up the change.
  function setInputValue(elem, newValue) {
    var valueSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value') ||
                      Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'value');
    if (valueSetter && valueSetter.set) {
      try {
        valueSetter.set.call(elem, newValue);
      } catch(e) {
        elem.value = newValue;
      }
    } else {
      elem.value = newValue;
    }
    try {
      var event = new Event('input', { bubbles: true });
      Object.defineProperty(event, 'target', {
        value: { value: elem.value, name: elem.name, id: elem.id },
        enumerable: true
      });
      elem.dispatchEvent(event);
      log("[setInputValue] dispatched input event, value='" + elem.value + "'");
    } catch(e) {
      log("[setInputValue] input event failed: " + e.message);
    }
  }

  // Dispatches Enter keydown/keypress/keyup — React onKeyDown handlers listen
  // for keydown.
  function dispatchEnter(elem) {
    var opts = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
    elem.dispatchEvent(new KeyboardEvent('keydown', opts));
    elem.dispatchEvent(new KeyboardEvent('keypress', opts));
    elem.dispatchEvent(new KeyboardEvent('keyup', opts));
    log("[dispatchEnter] Enter keydown/keypress/keyup dispatched");
  }

  // Falls back to form submission or send-button click if Enter doesn't work.
  function submitViaForm(input) {
    var form = input.closest('form');
    if (form) {
      log("[submitViaForm] submitting chat form");
      try { form.requestSubmit ? form.requestSubmit() : form.submit(); return; } catch(e) {
        log("[submitViaForm] form submit failed: " + e.message);
      }
    }
    var container = form || input.parentElement;
    var btn = container && container.querySelector('button[type="submit"], button[aria-label*="send" i], button[title*="send" i]');
    if (btn) {
      log("[submitViaForm] clicking chat send button");
      btn.click();
    } else {
      log("[submitViaForm] no chat form/send button found — message may not have been sent");
    }
  }

  // Main retry loop: find input, type "Hello", dispatch Enter, verify submit.
  function trySendHello(attempt) {
    if (attempt === undefined) attempt = 0;
    if (attempt > 30) {
      log("[trySendHello] giving up after " + attempt + " attempts");
      return;
    }

    var input = document.querySelector('textarea.chat-input-textarea');
    if (!input) input = document.querySelector('textarea[placeholder*="message" i], textarea[placeholder*="chat" i]');
    if (!input) input = document.querySelector('textarea');
    if (!input) input = document.querySelector('input[type="text"][placeholder*="message" i], input[type="text"][placeholder*="chat" i]');
    if (!input) input = document.querySelector('input[type="text"]');
    if (!input) input = document.querySelector('input');
    if (!input) {
      log("[trySendHello] no input found, attempt " + attempt + ", retrying...");
      setTimeoutTracked(function() { trySendHello(attempt + 1); }, 500);
      return;
    }

    log("[trySendHello] found input: " + (input.className || 'no class') + ", tag: " + input.tagName);
    input.focus();

    setInputValue(input, "Hello");
    log("[trySendHello] set Hello value, input.value='" + input.value + "'");

    setTimeoutTracked(function() {
      dispatchEnter(input);
      setTimeoutTracked(function() {
        if (input.value === '') {
          log("[trySendHello] textarea cleared — Hello submitted");
        } else {
          log("[trySendHello] textarea NOT cleared (value='" + input.value + "'), trying form submit");
          submitViaForm(input);
        }
      }, 800);
    }, 500);
  }

  function start() {
    log("[start] readyState=" + document.readyState);
    setTimeoutTracked(function() { trySendHello(0); }, 1500);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
