(function autoHello() {
  if (window.__piAutoHelloDone) return;
  window.__piAutoHelloDone = true;
  if (window.javaBridge) javaBridge.log("[autoHello] IIFE started");

  function log(msg) {
    try { if (window.javaBridge) javaBridge.log("[autoHello] " + msg); } catch(e) {}
  }

  function setReactInputValue(elem, newValue) {
    try {
      var valueSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value') ||
                    Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'value');
      if (valueSetter && valueSetter.set) {
        valueSetter.set.call(elem, newValue);
      } else {
        elem.value = newValue;
      }
    } catch(e) {
      try { elem.value = newValue; } catch(e2) {}
    }

    try {
      var rKey = null;
      for (var k in elem) {
        if (k.startsWith('__reactFiber') || k.startsWith('__react')) {
          rKey = k;
          break;
        }
      }
      if (rKey) {
        var fiber = elem[rKey];
        var props = fiber.memoizedProps || fiber.props;
        if (props && props.onChange) {
          var fakeEvent = {
            target: elem,
            currentTarget: elem,
            preventDefault: function(){},
            stopPropagation: function(){},
            persist: function(){}
          };
          Object.defineProperty(fakeEvent, 'target', {
            get: function() { return { value: elem.value, name: elem.name, id: elem.id }; },
            enumerable: true
          });
          props.onChange(fakeEvent);
          log("[setReactInputValue] called props.onChange via fiber " + rKey);
        }
      }
    } catch(e) {
      // Silent fail - fallback below
    }

    try {
      elem.dispatchEvent(new Event('input', { bubbles: true }));
      log("[setReactInputValue] dispatched input event, value='" + elem.value + "'");
    } catch(e) {
      log("[setReactInputValue] input event failed: " + e.message);
    }
  }

  function trySendHello(attempt) {
    if (attempt === undefined) attempt = 0;
    if (attempt > 30) {
      log("[trySendHello] giving up after " + attempt + " attempts");
      return;
    }

    var input = document.querySelector('textarea.chat-input-textarea');
    if (!input) input = document.querySelector('textarea');
    if (!input) input = document.querySelector('input[type="text"]');
    if (!input) input = document.querySelector('input');
    if (!input) {
      log("[trySendHello] no input found, attempt " + attempt + ", retrying...");
      setTimeout(function() { trySendHello(attempt + 1); }, 500);
      return;
    }

    log("[trySendHello] found input: " + (input.className || 'no class') + ", tag: " + input.tagName);
    input.focus();

    setReactInputValue(input, "Hello");
    log("[trySendHello] set Hello value, input.value='" + input.value + "'");

    setTimeout(function() {
      log("[setTimeout] setTimeout: pressing Enter on textarea");
      var enterDown = new KeyboardEvent('keydown', {
        key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
        bubbles: true, cancelable: true
      });
      var enterUp = new KeyboardEvent('keyup', {
        key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
        bubbles: true, cancelable: true
      });
      input.dispatchEvent(enterDown);
      input.dispatchEvent(enterUp);
      log("[setTimeout] setTimeout: Enter key dispatched on textarea");
    }, 500);
  }

  function start() {
    log("[start] readyState=" + document.readyState);
    setTimeout(function() { trySendHello(0); }, 1500);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
