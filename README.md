# JavaFX WebView App

A JavaFX desktop browser that connects to a local pi-web server
(`http://127.0.0.1:30141`) through an interposing HTTP proxy. The app loads
the pi-web chat UI, automatically types and submits "Hello" (dev-only), and
displays the server's SSE-streamed reply in the WebView.

## What this solves

JavaFX `WebView` cannot connect reliably to localhost servers. It fails
origin checks, sends the wrong `User-Agent`, does not handle SSE (Server-Sent
Events) streaming, and mishandles chunked transfer encoding. pi-web requires a
browser-like `User-Agent`, correct `Origin`/`Referer` headers, and streams
chat replies via SSE. This app works around all of those limitations with a
local HTTP proxy and a JavaScript EventSource polyfill.

## Prerequisites

| Component | Version | Notes |
|-----------|---------|-------|
| JDK | 21+ | Bundled in `./jdk` |
| Maven | 3.9+ | Bundled in `./maven` |
| pi-web | running | Must be listening on `http://127.0.0.1:30141` |
| OS | Windows | This project targets Windows (PowerShell launcher) |

Start pi-web unless already running:

```bash
pi-web --no-open --port 30141
```

## How to run

**Always use `./run.ps1`. Do not run `mvn javafx:run` directly** — it bypasses
JVM argument setup, pre-build compilation, and working-directory restoration.

```powershell
# Default: DEBUG logging (shows all inter-component communication)
./run.ps1

# Less noise: only WARN and ERROR
./run.ps1 -LogLevel WARN

# Only errors
./run.ps1 -LogLevel ERROR

# Skip compilation (faster iteration when code hasn't changed)
./run.ps1 -SkipBuild

# Skip compilation + minimal logging
./run.ps1 -SkipBuild -LogLevel WARN
```

The script:
1. Sets `JAVA_HOME` and `MAVEN_HOME` to the bundled tools
2. Compiles the project
3. Passes `-Dpi.log.level=<level>` as a JVM argument to JavaFX
4. Launches the JavaFX application
5. Restores your original working directory on exit

## Architecture

The application is split into components with clear responsibilities:

```
┌─────────────────────────────────────────────────────────────┐
│                         WebViewApp                          │
│  (GUI only: stage, scene, address bar, nav buttons)         │
│                    owns                                     │
│              ┌─────────────────────────┐                   │
│              │     ProxiedWebView      │                   │
│              │  (WebView wrapper +     │                   │
│              │   proxy interposition)  │                   │
│              │                         │                   │
│              │  owns ┌─────────────┐   │                   │
│              │       │ ProxyServer │   │                   │
│              │       │ (HTTP proxy │   │                   │
│              │        header rewrite│ │                   │
│              │         SSE pipe)   │   │                   │
│              │  owns ┌─────────────┐   │                   │
│              │       │ SSEHandler  │   │                   │
│              │       │ (SSE stream │   │                   │
│              │        lifecycle)   │   │                   │
│              │  owns ┌─────────────┐   │                   │
│              │       │ AutoHello   │   │                   │
│              │       │ (dev-only)  │   │                   │
│              │  uses ┌─────────────┐   │                   │
│              └──────►│   JSBridge  │───┼──────────────────┤
│                      │ (window.    │◄──┤ Page JS          │
│                      │  javaBridge)│   │ postMessage()    │
│                      └─────────────┘   │                  │
│                                        │                  │
│                                 bridge.js                 │
│                                 autoHello.js              │
│                                (classpath JS)             │
└─────────────────────────────────────────────────────────────┘
```

### Components

| Component | File | Role |
|-----------|------|------|
| **WebViewApp** | `WebViewApp.java` | GUI only: JavaFX stage, scene, address bar, navigation buttons. Owns `ProxiedWebView`. |
| **ProxiedWebView** | `ProxiedWebView.java` | Wraps `WebView` (composition — `WebView` is `final`). Owns `ProxyServer`, `SSEHandler`, `AutoHello`. Uses `JSBridge`. Interposes proxy between page and network. |
| **ProxyServer** | `ProxyServer.java` | HTTP proxy: rewrites `Origin`/`Referer`, injects `User-Agent`, pipes SSE streams, handles chunked bodies, SSRF validation. |
| **JSBridge** | `JSBridge.java` | `window.javaBridge`: captures `console.log`, `console.error`, `window.onerror`, `postMessage()`. |
| **SSEHandler** | `SSEHandler.java` | SSE stream lifecycle: open → read → forward events back to page. Bounded thread pool (4 threads). |
| **AutoHello** | `AutoHello.java` | Dev-only: auto-types "Hello" and submits on page load. Removable in production. |
| **AppConfig** | `AppConfig.java` | Centralized configuration: target origin, proxy host, buffer size, timeouts, UA, log level. |
| **AppLogger** | `logging/AppLogger.java` | Level-based logging (TRACE/DEBUG/INFO/WARN/ERROR) to `System.out`. |
| **ResourceUtil** | `ResourceUtil.java` | Classpath resource loading for JS files. |

### JS resources (classpath)

| File | Purpose |
|------|---------|
| `bridge.js` | Bridge install + EventSource polyfill + SSE state management |
| `autoHello.js` | Auto "Hello" IIFE (dev-only smoke test) |

## Communication flow (end-to-end)

```
1. User types URL in address bar → WebViewApp.handleAddressBar()
2. WebViewApp → ProxiedWebView.navigateTo(url)
3. ProxiedWebView → ProxyServer (rewrite URL through local proxy)
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

## Logging

All directional communication between components is logged at DEBUG/TRACE:

```
User -> ProxiedWebView     (address bar, navigation)
ProxiedWebView -> Proxy    (URL rewrite)
Proxy -> Target / -> User  (HTTP request/response both directions)
Page JS -> JSBridge        (console.log/error, postMessage __PI_SSE_START__/STOP__)
JSBridge -> ProxiedWebView (SSE routed internally to SSEHandler)
ProxiedWebView -> SSEHandler (SSE start/stop routing)
SSEHandler -> Target       (SSE stream open)
Target -> SSEHandler       (SSE events/chunks)
SSEHandler -> Page JS      (sendToPage → receiveMessageFromJava)
Page JS -> AutoHello       (DOM interaction)
```

Set the level at runtime: `./run.ps1 -LogLevel DEBUG` (default) or `-LogLevel WARN`/`ERROR` for less noise.

## Configuration

All tunable values are in `AppConfig.java`. Do not hardcode `127.0.0.1`, `8192`,
or User-Agent strings outside this class.

| Constant | Value | Purpose |
|----------|-------|---------|
| `TARGET_ORIGIN` | `http://127.0.0.1:30141` | Target origin (scheme://host:port) |
| `PROXY_HOST` | `127.0.0.1` | Proxy bind host |
| `BUFFER_SIZE` | `8192` | Streaming buffer size (bytes) |
| `USER_AGENT` | Chrome 130 UA string | User-Agent for proxy and SSE client |
| `SSE_READ_TIMEOUT` | `300000` | SSE stream read timeout (ms) |
| `CONNECT_TIMEOUT` | `10000` | Connection timeout (ms) |
| `MAX_REQUEST_BODY_SIZE` | `10485760` | Max request body size (bytes, 10 MB) |
| `AUTOHELLO_ENABLED` | `true` | Auto-type "Hello" on page load |
| `LOG_LEVEL` | `TRACE` | Default log level |

### Production housekeeping

- Set `AUTOHELLO_ENABLED = false` in `AppConfig.java` to remove AutoHello
- Use `./run.ps1 -LogLevel WARN` or `-LogLevel ERROR` to reduce log noise
- Review `AppConfig.USER_AGENT` if the target server changes browser sniffing

## Verification checklist

After any change, confirm:

- [ ] `./run.ps1` starts without errors
- [ ] Logs appear at the configured level
- [ ] AutoHello submits "Hello" on page load (unless disabled)
- [ ] The GUI shows the server's response to "Hello"
- [ ] No `System.out`/`System.err` in any Java file (use `AppLogger`)
- [ ] No hardcoded `127.0.0.1`, `8192`, or User-Agent literals outside `AppConfig`
- [ ] No inline JS strings longer than one line in Java files

## Build only

```powershell
cd webview-app
./mvn.cmd package
```

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

## Known limitations

- JavaFX `WebView` is `final` — cannot extend; composition is used instead
- PowerShell corrupts Java text blocks (`"""`) — JS is loaded from resources, not inlined in Java
- The proxy binds to `127.0.0.1` only — not exposed to other hosts
- AutoHello is dev-only and targets the pi-web chat input specifically
