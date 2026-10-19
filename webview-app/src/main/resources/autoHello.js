(function autoHello() {
  // ===========================================================================
  // autoHello.js — Dev-only: auto-types "Hello" and submits on page load
  // ===========================================================================
  //
  // PURPOSE:
  //   Smoke-tests the end-to-end Hello → reply flow without manual user
  //   interaction. On page load it:
  //     1. Finds the chat input (tries multiple selectors in order)
  //     2. Sets its value to "Hello" (React-compatible setter + input/change events)
  //     3. Clicks the send button (primary) or dispatches Enter (fallback)
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
  //   are silently swallowed.
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
  // input AND change events so React's controlled component picks up the change.
  // Uses the correct prototype (HTMLInputElement vs HTMLTextAreaElement)
  // based on the element's tag name.
  function setInputValue(elem, newValue) {
    var proto = elem.tagName === 'TEXTAREA'
      ? window.HTMLTextAreaElement.prototype
      : window.HTMLInputElement.prototype;
    var valueSetter = Object.getOwnPropertyDescriptor(proto, 'value');
    if (valueSetter && valueSetter.set) {
      try {
        valueSetter.set.call(elem, newValue);
      } catch(e) {
        elem.value = newValue;
      }
    } else {
      elem.value = newValue;
    }
    // Dispatch input event — React onChange listens for this.
    try {
      var inputEvent = new Event('input', { bubbles: true, cancelable: true });
      elem.dispatchEvent(inputEvent);
      log("[setInputValue] dispatched input event, value='" + elem.value + "'");
    } catch(e) {
      log("[setInputValue] input event failed: " + e.message);
    }
    // Dispatch change event as well — some React setups listen for this.
    try {
      var changeEvent = new Event('change', { bubbles: true, cancelable: true });
      elem.dispatchEvent(changeEvent);
      log("[setInputValue] dispatched change event, value='" + elem.value + "'");
    } catch(e) {
      log("[setInputValue] change event failed: " + e.message);
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

  // Find a send button near the input. Tries multiple selectors because chat
  // UIs vary widely (icon buttons, submit buttons, aria-label buttons, etc.).
  function findSendButton(input) {
    var container = input.closest('form') || input.parentElement;
    var root = container || document;

    // 1. Form submit button
    var btn = root.querySelector('button[type="submit"]');
    if (btn) return btn;

    // 2. Buttons with send-related aria-label or title
    var labelSelectors = [
      'button[aria-label*="send" i]',
      'button[title*="send" i]',
      'button[aria-label*="submit" i]',
      'button[title*="submit" i]'
    ];
    for (var s = 0; s < labelSelectors.length; s++) {
      btn = root.querySelector(labelSelectors[s]);
      if (btn) return btn;
    }

    // 3. Buttons containing an SVG icon (common for send icons)
    var svgBtns = root.querySelectorAll('button');
    for (var b = 0; b < svgBtns.length; b++) {
      if (svgBtns[b].querySelector('svg, i, [class*="send" i], [class*="submit" i]')) {
        return svgBtns[b];
      }
    }

    // 4. Any visible button near the input
    var nearby = root.querySelectorAll('button');
    for (var n = 0; n < nearby.length; n++) {
      if (nearby[n] !== input && nearby[n].offsetParent !== null) {
        return nearby[n];
      }
    }

    return null;
  }

  // Clicks the send button if found.
  function submitViaButton(input) {
    var btn = findSendButton(input);
    if (btn) {
      log("[submitViaButton] found send button: " + (btn.className || 'no class') + ", tag: " + btn.tagName + ", disabled=" + btn.disabled);
      if (btn.disabled) {
        log("[submitViaButton] send button is disabled — cannot click");
        return false;
      }
      try {
        btn.click();
        log("[submitViaButton] clicked send button");
        return true;
      } catch(e) {
        log("[submitViaButton] button click failed: " + e.message);
      }
    } else {
      log("[submitViaButton] no send button found");
    }
    return false;
  }

  // Falls back to form submission if button click doesn't work.
  function submitViaForm(input) {
    var form = input.closest('form');
    if (form) {
      log("[submitViaForm] submitting chat form");
      try { form.requestSubmit ? form.requestSubmit() : form.submit(); return true; } catch(e) {
        log("[submitViaForm] form submit failed: " + e.message);
      }
    }
    return false;
  }

  // Main retry loop: find input, type "Hello", submit, verify.
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

    // Give React time to process the input/change events before submitting.
    setTimeoutTracked(function() {
      // Primary: click the send button (more reliable than synthetic keyboard events in React).
      var submitted = submitViaButton(input);

      // Fallback: dispatch Enter key if button click didn't happen.
      if (!submitted) {
        log("[trySendHello] button click not performed, dispatching Enter");
        dispatchEnter(input);
      }

      // Second fallback: form submission.
      if (!submitted) {
        submitted = submitViaForm(input);
      }

      // Wait longer for React to process submission and clear the textarea.
      setTimeoutTracked(function() {
        if (input.value === '') {
          log("[trySendHello] textarea cleared — Hello submitted");
        } else {
          log("[trySendHello] textarea NOT cleared (value='" + input.value + "'), will retry submission");
          // Retry submission once more after a longer delay
          setTimeoutTracked(function() {
            submitViaButton(input);
            submitViaForm(input);
          }, 1500);
        }
      }, 1500);
    }, 1000);
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