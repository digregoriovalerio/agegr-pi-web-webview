# WebView App

JavaFX WebView app connecting to http://localhost:30141 (pi-web server).

## Prerequisites

Start pi-web unless already running:

```bash
pi-web --no-open --port 30141
```

Tools: JDK (`./jdk`), Maven (`./maven`), PowerShell, Windows.

## Run

```powershell
./run.ps1
```

Defaults: `-LogLevel DEBUG` (shows all inter-component communication), `-Dpi.autohello.enabled=true`.

Reduce noise:

```powershell
./run.ps1 -LogLevel INFO   # or WARN, ERROR, TRACE
```

Always shut down before relaunching.

## Architecture (concise)

| Component | File | Role |
|-----------|------|------|
| **WebViewApp** | `WebViewApp.java` | GUI only: stage, scene, address bar, nav buttons. Owns ProxiedWebView. |
| **ProxiedWebView** | `ProxiedWebView.java` | Wraps `WebView` (composition — `WebView` is `final`). Owns ProxyServer, SSEHandler, AutoHello. Interposes proxy between page and network. |
| **ProxyServer** | `ProxyServer.java` | HTTP proxy: rewrites Origin/Referer, injects User-Agent, pipes SSE streams. |
| **JSBridge** | `JSBridge.java` | `window.javaBridge`: captures `console.log`, `console.error`, `window.onerror`, `postMessage()`. |
| **SSEHandler** | `SSEHandler.java` | SSE stream lifecycle: open → read → forward events back to page. |
| **AutoHello** | `AutoHello.java` | Dev-only: auto-types "Hello" and submits. Removable in production. |
| **AppConfig** | `AppConfig.java` | Shared config: User-Agent, buffer size, proxy/target host/port, timeouts. |
| **AppLogger** | `logging/AppLogger.java` | Level-based logging (TRACE/DEBUG/INFO/WARN/ERROR) to `System.out`. |

### JS resources (classpath)

- `src/main/resources/bridge.js` — bridge install + EventSource polyfill + SSE state
- `src/main/resources/autoHello.js` — auto "Hello" IIFE

## Detailed Architecture

### Component dependency graph

```
WebViewApp ──owns──> ProxiedWebView ──owns──> ProxyServer
                              │              (HTTP proxy, header rewrite)
                              ├─owns──> SSEHandler  (SSE stream lifecycle)
                              ├─owns──> AutoHello   (dev-only, removable)
                              └─uses──> JSBridge    (window.javaBridge)
                                    ↑
                              Page JS calls postMessage()
```

Ownership means lifecycle management (create/start/stop). "Uses" means collaboration without ownership.

### Interface contracts

| From | To | Contract |
|------|-----|----------|
| WebViewApp | ProxiedWebView | `navigateTo(url)`, `start()`, `stop()`, `setOnPageLoaded()`, `setMessageCallback()`, `getWebView()` |
| ProxiedWebView | ProxyServer | `start()`, `stop()`, `getListenPort()`, `getTargetOrigin()` |
| ProxiedWebView | SSEHandler | `start(url)`, `stop(url)`, `stopAll()` (routed via JSBridge callback) |
| ProxiedWebView | JSBridge | `window.setMember("javaBridge", bridge)`, JS calls `postMessage(msg)` |
| JSBridge | Page JS | `log(msg)`, `error(msg)`, `postMessage(msg)` |
| SSEHandler | Page JS | `sendToPage(message)` → `window.receiveMessageFromJava(msg)` |
| Page JS | AutoHello | DOM manipulation (sets input value, dispatches Enter) |
| All | AppLogger | `info/debug/trace/warn/error(source, msg)`, `comm(from,to,direction,msg)` |

### Communication flow (end-to-end)

```
1. User types URL in address bar → WebViewApp.handleAddressBar()
2. WebViewApp → ProxiedWebView.navigateTo(url)
3. ProxiedWebView → ProxyServer (rewrite URL through proxy)
4. ProxyServer → Target pi-web (forward HTTP request with fixed headers)
5. Target → ProxyServer (HTTP response, SSE stream if event-stream)
6. ProxyServer → ProxiedWebView (WebEngine.load result)
7. Page loads → ProxiedWebView.installJSBridge() → injects bridge.js
8. Page JS EventSource polyfill → JSBridge.postMessage("__PI_SSE_START__:url")
9. JSBridge → ProxiedWebView callback → SSEHandler.start(url)
10. SSEHandler → Target (HTTP GET SSE endpoint)
11. Target → SSEHandler (SSE events/chunks)
12. SSEHandler → ProxiedWebView.sendToPage(msg)
13. ProxiedWebView → Page JS (window.receiveMessageFromJava(msg))
14. Page JS → AutoHello (if enabled): find input, type "Hello", press Enter
```

### Logging model

Every directional edge in the graph above is instrumented:
- `AppLogger.comm(from, to, direction, message)` — DEBUG level, shows who talks to whom
- `AppLogger.commTrace(from, to, direction, message)` — TRACE level, byte-level detail
- `AppLogger.info/debug/warn/error(source, message)` — lifecycle and errors

To add a new communication edge, add a `comm()` call at the sender side with `(source, destination, "DIRECTION", message)`.

## Logging

All directional communication between entities is logged at `DEBUG`/`TRACE`:

```
User -> ProxiedWebView     (address bar, navigation)
ProxiedWebView -> Proxy    (URL rewrite)
Proxy -> Target / -> User  (HTTP request/response both directions)
Page JS -> JSBridge        (console.log/error, postMessage __PI_SSE_START__/STOP__)
JSBridge -> ProxiedWebView (SSE routed internally to SSEHandler; other msgs to WebViewApp)
ProxiedWebView -> SSEHandler (SSE start/stop routing)
SSEHandler -> Target       (SSE stream open)
Target -> SSEHandler       (SSE events/chunks)
SSEHandler -> Page JS      (sendToPage → receiveMessageFromJava)
Page JS -> AutoHello       (DOM interaction)
```

Set level at runtime: `AppLogger.INSTANCE.setLevel(AppLogger.Level.DEBUG)` or via `-Dpi.log.level=DEBUG`.

## Configuration (system properties)

| Property | Default | Purpose |
|----------|---------|---------|
| `pi.log.level` | `INFO` | Log verbosity (TRACE/DEBUG/INFO/WARN/ERROR) |
| `pi.autohello.enabled` | `true` | Disable in production: `-Dpi.autohello.enabled=false` |
| `pi.user.agent` | browser-like UA | User-Agent for proxy and SSE client |
| `pi.buffer.size` | `8192` | Streaming buffer size (bytes) |
| `pi.proxy.host` | `127.0.0.1` | Proxy bind host |
| `pi.target.host` | `127.0.0.1` | Target host |
| `pi.target.port` | `30141` | Target port |

## Housekeeping (future maintenance)

### Adding a new entity/component

1. Create the class under `com.example.webview`
2. Instantiate it in `ProxiedWebView` (if owned) or `WebViewApp` (if GUI)
3. Register it in the dependency graph table above
4. Add `AppLogger.comm()` calls for every directional communication it participates in
5. Add its JS resource to `src/main/resources/` if it injects JavaScript
6. Update AGENTS.md: component table, dependency graph, communication flow, config table

### Modifying an existing component

1. Do not add `System.out.println` or `System.err.println` — use `AppLogger`
2. Do not hardcode `127.0.0.1`, `8192`, or User-Agent strings — use `AppConfig`
3. Do not inline large JavaScript strings in Java — put them in `src/main/resources/*.js` and load via `readResource()`
4. Keep `WebViewApp` GUI-only: no networking, proxy, SSE, or JS bridge logic there
5. Keep `AutoHello` removable: always gate behind `autoHello.isEnabled()` and `-Dpi.autohello.enabled=false`

### Production housekeeping

- Set `-Dpi.autohello.enabled=false` to remove AutoHello
- Set `-Dpi.log.level=WARN` or `ERROR` to reduce log noise
- Review `AppConfig.getUserAgent()` if the target server changes its browser sniffing
- Ensure `ProxyServer` header rewrites (Origin/Referer) still match the target origin

### Verification checklist (run after every change)

- [ ] `./run.ps1` starts without errors
- [ ] Logs appear at the configured level (`-LogLevel DEBUG` shows all inter-component comms)
- [ ] AutoHello submits "Hello" on page load (unless disabled)
- [ ] GUI shows the response to "Hello"
- [ ] No `System.out/err` in any Java file
- [ ] No hardcoded `127.0.0.1`, `8192`, or User-Agent literals outside `AppConfig`
- [ ] No inline JS strings longer than one line in Java files
- [ ] Have you sync-ed the AGENTS.md?

## AGENTS.md sync discipline

AGENTS.md must be updated with every modification to code, configuration, or architecture. Before declaring a task complete:

1. Update the component table if responsibilities changed
2. Update interface contracts if method signatures changed
3. Update communication flow if new edges were added
4. Update configuration table if new system properties were introduced
5. Update housekeeping rules if new constraints were discovered
6. Update file layout if files were added/removed/moved

## Discoveries / Lessons Learned (token-saver for future sessions)

| Discovery | Impact | Where documented |
|-----------|--------|------------------|
| JavaFX `WebView` is `final` | Cannot extend — use composition (`BorderPane` wrapper owning `WebView`) | `ProxiedWebView.java` |
| PowerShell corrupts Java text blocks (`"""`) | Multi-line JS strings in Java files break compilation — load JS from `src/main/resources/*.js` via `readResource()` | `bridge.js`, `autoHello.js` |
| `java.util.logging` is overridden by JDK's `logging.properties` | Logs disappear silently — use direct `System.out` with `AppLogger`, not `java.util.logging.Logger` | `AppLogger.java` |
| Maven `javafx:run` ignores `-D` flags unless passed via jvmArguments | Use `-Djavafx.run.jvmArguments="-Dpi.log.level=DEBUG ..."` in `run.ps1` | `run.ps1` |
| `run.ps1` must reset cwd on exit | Wrap `Push-Location`/`Pop-Location` in `try/finally` so the caller's cwd is restored | `run.ps1` |
| AutoHello must be removable in production | Gate behind `pi.autohello.enabled` system property (default `true`) | `AutoHello.java`, `run.ps1` |
| `AppConfig` centralizes all tunable values | User-Agent, buffer size, proxy/target host/port all in one place — no hardcoding | `AppConfig.java` |
| SSE messages (`__PI_SSE_START__`/`__PI_SSE_STOP__`) routed internally | `ProxiedWebView` handles them via JSBridge callback — `WebViewApp` never sees them | `ProxiedWebView.java` |

## File layout

```
webview-app/
  pom.xml
  src/main/java/com/example/webview/
    WebViewApp.java          # GUI only
    ProxiedWebView.java      # WebView wrapper + proxy interposition + owns all sub-components
    ProxyServer.java         # HTTP proxy server
    JSBridge.java            # JS-to-Java bridge
    SSEHandler.java          # SSE event streaming
    AutoHello.java           # Dev-only auto "Hello" submission
    AppConfig.java           # Centralized configuration
    logging/
      AppLogger.java         # Centralized level-based logging
  src/main/resources/
    bridge.js                # JS bridge + EventSource polyfill
    autoHello.js             # AutoHello IIFE
  target/
    classes/
```
