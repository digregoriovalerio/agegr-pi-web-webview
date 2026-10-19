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

Skip build (faster iteration):

```powershell
./run.ps1 -SkipBuild
```

Always shut down before relaunching.

> AutoHello is disabled by default in `AppConfig.java`. To enable, change `AUTOHELLO_ENABLED = true`.

## Architecture (concise)

| Component | File | Role |
|-----------|------|------|
| **WebViewApp** | `WebViewApp.java` | GUI only: stage, scene, address bar, nav buttons. Owns ProxiedWebView. |
| **ProxiedWebView** | `ProxiedWebView.java` | Wraps `WebView` (composition — `WebView` is `final`). Owns ProxyServer, SSEHandler, AutoHello. Interposes proxy between page and network. |
| **ProxyServer** | `ProxyServer.java` | HTTP proxy: rewrites Origin/Referer, injects User-Agent, pipes SSE streams. Handles chunked request bodies, validates paths for SSRF. |
| **JSBridge** | `JSBridge.java` | `window.javaBridge`: captures `console.log`, `console.error`, `window.onerror`, `postMessage()`. |
| **SSEHandler** | `SSEHandler.java` | SSE stream lifecycle: open → read → forward events back to page. Uses bounded thread pool. |
| **AutoHello** | `AutoHello.java` | Dev-only: auto-types "Hello" and submits. Removable in production. |
| **AppConfig** | `AppConfig.java` | Shared config: User-Agent, buffer size, proxy/target host/port, timeouts, max body size. |
| **AppLogger** | `logging/AppLogger.java` | Level-based logging (TRACE/DEBUG/INFO/WARN/ERROR) to `System.out` with message truncation. |
| **ResourceUtil** | `ResourceUtil.java` | Classpath resource loading with context ClassLoader fallback. |

### JS resources (classpath)

- `src/main/resources/bridge.js` — bridge install + EventSource polyfill + SSE state
- `src/main/resources/autoHello.js` — auto "Hello" IIFE

### Utility classes

- `src/main/java/com/example/webview/ResourceUtil.java` — classpath resource loading with context ClassLoader fallback

## Detailed Architecture

### Component dependency graph

```
WebViewApp ──owns──> ProxiedWebView ──owns──> ProxyServer
                              │              (HTTP proxy, header rewrite, chunked encoding)
                              ├─owns──> SSEHandler  (SSE stream lifecycle, bounded pool)
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

Set level at runtime: `AppLogger.INSTANCE.setLevel(AppLogger.Level.DEBUG)`.

## Configuration (hardcoded in `AppConfig.java`)

| Constant | Value | Purpose |
|----------|-------|---------|
| `TARGET_ORIGIN` | `http://127.0.0.1:30141` | Target origin (scheme://host:port) |
| `PROXY_HOST` | `127.0.0.1` | Proxy bind host |
| `BUFFER_SIZE` | `8192` | Streaming buffer size (bytes) |
| `USER_AGENT` | Chrome 130 UA string | User-Agent for proxy and SSE client |
| `SSE_READ_TIMEOUT` | `300000` | SSE stream read timeout (ms) |
| `CONNECT_TIMEOUT` | `10000` | Connection timeout (ms) |
| `MAX_REQUEST_BODY_SIZE` | `10485760` | Max request body size (bytes, 10MB) |
| `SOCKET_TIMEOUT` | `300000` | Client socket read timeout (ms) |
| `SSE_POOL_SIZE` | `4` | SSE reader thread pool size |
| `AUTOHELLO_ENABLED` | `false` | Auto-type "Hello" on page load (dev-only) |
| `LOG_LEVEL` | `WARN` | Default log level |

## Fixes for missing Hello reply (this session)

| Fix | File | Root Cause |
|-----|------|------------|
| JSBridge strong reference | `ProxiedWebView.java` | JavaFX `setMember()` uses weak references — `JSBridge` was GC'd after install, killing all later `javaBridge.*` calls from JS. Fixed by holding a `List<JSBridge>` strong reference. |
| Proxy POST body double-read | `ProxyServer.java` | `readRequest()` already consumes the full body; `handleClient()` tried to read it again from `clientIn` → blocks until read timeout. Fixed by extracting body from already-read `reqBuf`. |
| `getOutputStream()` + explicit `connect()` | `ProxyServer.java` | `getOutputStream()` implicitly connects; explicit `conn.connect()` afterward throws "Already connected". Fixed by removing all explicit `conn.connect()` calls. |
| SSE relative URL resolution | `SSEHandler.java` | pi-web uses `new EventSource("/api/agent/${id}/events")` — relative URL. `URI.create(relative).toURL()` throws. Fixed by resolving against target origin. |
| EventSource polyfill fallback | `bridge.js` | If `javaBridge.postMessage()` throws, polyfill silently failed. Fixed by catching failure and falling back to `NativeEventSource`. |

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
5. Keep `AutoHello` removable

### Production housekeeping

- Set `AUTOHELLO_ENABLED = false` in `AppConfig.java` to remove AutoHello
- Set `AppConfig.LOG_LEVEL` to `TRACE` for verbose logging
- Review `AppConfig.USER_AGENT` if the target server changes its browser sniffing
- Ensure `ProxyServer` header rewrites (Origin/Referer) still match the target origin
- `ProxyServer` strips `Set-Cookie` — cookie-based auth cannot work through the proxy (deliberate)

### Verification checklist (run after every change)

- [ ] `./run.ps1` starts without errors (MUST run via `./run.ps1` — NOT `mvn javafx:run` directly)
- [ ] Logs appear at the configured level (`AppConfig.LOG_LEVEL` set to `TRACE` shows all inter-component comms)
- [ ] AutoHello submits "Hello" on page load (unless disabled)
- [ ] GUI shows the response to "Hello"
- [ ] `./mvn test` passes (unit tests for proxy/path parsing)
- [ ] No `System.out/err` in any Java file
- [ ] No hardcoded `127.0.0.1`, `8192`, or User-Agent literals outside `AppConfig`
- [ ] No inline JS strings longer than one line in Java files
- [ ] Have you sync-ed the AGENTS.md?

> **IMPORTANT**: Always run the application via `./run.ps1`. This script:
> - Sets up `JAVA_HOME` and `MAVEN_HOME` correctly
> - Runs pre-build compilation step
> - Performs pi-web health check
> - Restores working directory on exit
> Running `mvn javafx:run` directly bypasses all of the above and will fail or behave incorrectly.

## AGENTS.md sync discipline

AGENTS.md must be updated with every modification to code, configuration, or architecture. Before declaring a task complete:

1. Update the component table if responsibilities changed
2. Update interface contracts if method signatures changed
3. Update communication flow if new edges were added
4. Update configuration table if new system properties were introduced
5. Update housekeeping rules if new constraints were discovered
6. Update file layout if files were added/removed/moved

## Behavior changes in this session
- Address-bar / location sync now uses `startsWith("http://")` / `startsWith("https://")` (the previous `String.matches("^https?://")` never matched).
- `normalizeUrl` defaults to `http://` for scheme-less input so local plain-HTTP targets (e.g. pi-web) are reachable.
- Non-HTTP(S) URLs (e.g. `about:blank`) load directly without the proxy, so they cannot corrupt the proxy target origin or orphan SSE streams.
- Unreachable targets now yield `502 Bad Gateway` instead of a silent socket close; all responses carry `Connection: close`.
- SSE `stop()`/`stopAll()` close the underlying stream to unblock a blocked reader; incomplete trailing SSE data is discarded per spec.
- EventSource polyfill now supports `addEventListener`/`removeEventListener`.
- `printStackTrace()` replaced with `AppLogger.error(source, msg, throwable)`.
- Low-severity hygiene: hardcoded timeouts/pool sizes moved to `AppConfig` (`SOCKET_TIMEOUT`, `SSE_POOL_SIZE`); `AppLogger` now defaults from `AppConfig.LOG_LEVEL`; dead `AppConfig` constructor removed; `Level.ordinal` renamed to `levelValue`; `ProxyServer.parseHeaders` skips the request line; request headers bounded at 64 KB; `autoHello.js` uses tag-name-aware value setter; SSE prefix constants shared via `JSBridge.SSE_START_PREFIX`/`SSE_STOP_PREFIX`; proxy thread names use a counter instead of `identityHashCode`; redundant `Platform.runLater` removed from address-bar listener; `ProxiedWebView.stop()` guards against null `sseHandler`.

## Discoveries / Lessons Learned (token-saver for future sessions)

| Discovery | Impact | Where documented |
|-----------|--------|------------------|
| JavaFX `WebView` is `final` | Cannot extend — use composition (`BorderPane` wrapper owning `WebView`) | `ProxiedWebView.java` |
| PowerShell corrupts Java text blocks (`"""`) | Multi-line JS strings in Java files break compilation — load JS from `src/main/resources/*.js` via `readResource()` | `bridge.js`, `autoHello.js` |
| `java.util.logging` is overridden by JDK's `logging.properties` | Logs disappear silently — use direct `System.out` with `AppLogger`, not `java.util.logging.Logger` | `AppLogger.java` |
| `run.ps1` must reset cwd on exit | Wrap `Push-Location`/`Pop-Location` in `try/finally` so the caller's cwd is restored | `run.ps1` |
| AutoHello must be removable in production | Gate behind `pi.autohello.enabled` system property (default `true`) | `AutoHello.java`, `run.ps1` |
| `AppConfig` centralizes all tunable values | User-Agent, buffer size, proxy/target host/port all in one place — no hardcoding | `AppConfig.java` |
| Use single `targetOrigin` string instead of separate host/port/scheme | Simplifies ProxyServer constructor, reduces config surface, matches RFC 6454 origin format | `ProxyServer.java`, `AppConfig.java` |
| SSE messages (`__PI_SSE_START__`/`__PI_SSE_STOP__`) routed internally | `ProxiedWebView` handles them via JSBridge callback — `WebViewApp` never sees them | `ProxiedWebView.java` |
| Unbounded thread pools cause OOM | Use `Executors.newFixedThreadPool()` with bounded size for proxy and SSE handlers | `ProxyServer.java`, `SSEHandler.java` |
| Chunked transfer encoding not handled by default | Must implement custom chunked request parsing in proxy | `ProxyServer.java` |
| SSRF via absolute-form request URIs | Validate request path starts with `/` and reject scheme://host paths | `ProxyServer.java` |
| Lambda in constructor triggers 'this escape' warning | Use `@SuppressWarnings("this-escape")` or lazy initialization | `ProxiedWebView.java` |
| `URL(String)` deprecated in Java 20+ | Use `URI.create(str).toURL()` instead | `ProxyServer.java`, `SSEHandler.java` |
| JavaFX `setMember()` uses weak references | Bridge GC'd after install — hold strong `List<JSBridge>` reference | `ProxiedWebView.java` |
| Proxy re-reads POST body from drained socket | POST blocks until read timeout — extract body from already-read `reqBuf` | `ProxyServer.java` |
| `getOutputStream()` + explicit `connect()` = "Already connected" | Remove all explicit `conn.connect()` calls | `ProxyServer.java` |
| pi-web uses relative EventSource URLs | Resolve relative SSE URLs against target origin | `SSEHandler.java` |
| EventSource polyfill silently fails when bridge unavailable | Fall back to `NativeEventSource` on `postMessage` failure | `bridge.js` |

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
    ResourceUtil.java        # Classpath resource loading utility
    logging/
      AppLogger.java         # Centralized level-based logging
  src/main/resources/
    bridge.js                # JS bridge + EventSource polyfill
    autoHello.js             # AutoHello IIFE
  target/
    classes/
```