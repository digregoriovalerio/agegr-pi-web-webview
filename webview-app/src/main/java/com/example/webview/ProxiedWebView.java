package com.example.webview;

import javafx.beans.value.ChangeListener;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.web.WebErrorEvent;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;

/**
 * A JavaFX {@link WebView} wrapper that routes all navigation through a local
 * {@link ProxyServer}.
 *
 * This works around JavaFX WebView's inability to connect to certain localhost
 * servers (e.g. pi-web on 127.0.0.1:30141). The proxy rewrites Origin/Referer
 * headers and injects a browser-like User-Agent so that server-side origin
 * checks and browser sniffing pass.
 *
 * Usage:
 *   ProxiedWebView view = new ProxiedWebView("127.0.0.1", 30141);
 *   Stage stage = new Stage();
 *   stage.setScene(new Scene(view, 1100, 750));
 *   stage.show();
 *   view.navigateTo("http://127.0.0.1:30141");
 *
 * Call {@link #stop()} when the application exits to shut down the proxy.
 */
public class ProxiedWebView extends BorderPane {

    private final WebView webView;
    private final ProxyServer proxy;
    private final TextField addressBar;
    private final String targetHost;
    private final int targetPort;
    private String currentUrl;

    /**
     * Create a proxied WebView.
     *
     * @param targetHost target host (e.g. "127.0.0.1")
     * @param targetPort target port (e.g. 30141)
     */
    public ProxiedWebView(String targetHost, int targetPort) {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.proxy = new ProxyServer(targetHost, targetPort, 0);

        webView = new WebView();

        // --- Address bar ---------------------------------------------------------
        addressBar = new TextField();
        addressBar.setPromptText("Enter a URL and press Enter (e.g. 127.0.0.1:30141)");
        HBox.setHgrow(addressBar, Priority.ALWAYS);
        addressBar.setOnAction(event -> navigateTo(addressBar.getText()));

        // Keep the address bar in sync when the user follows links inside the page.
        webView.getEngine().locationProperty().addListener((ChangeListener<String>) (obs, oldLoc, newLoc) -> {
            if (newLoc != null && newLoc.matches("^https?://") && !newLoc.equals(currentUrl)) {
                addressBar.setText(newLoc);
                currentUrl = newLoc;
            }
        });

        // --- Diagnostics ---------------------------------------------------------
        webView.getEngine().setOnError((WebErrorEvent event) -> {
            String msg = String.format(
                "WebView load error: %s (location=%s)", event.getMessage(), webView.getEngine().getLocation());
            System.err.println(msg);
        });

        webView.getEngine().getLoadWorker().stateProperty().addListener((ChangeListener<Worker.State>)
            (obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                System.out.println("Page loaded: " + webView.getEngine().getLocation());
                // Bridge JS console.log/error to Java stderr for debugging
                JSObject window = (JSObject) webView.getEngine().executeScript("window");
                window.setMember("javaBridge", new Object() {
                    public void log(String msg) { System.out.println("[JS] " + msg); }
                    public void error(String msg) { System.err.println("[JS] " + msg); }
                });
                webView.getEngine().executeScript("""
                    window.onerror = function(msg, url, line) {
                        javaBridge.error(msg + ' at ' + url + ':' + line);
                        return false;
                    };
                    var origLog = console.log.bind(console);
                    var origError = console.error.bind(console);
                    console.log = function() { origLog.apply(console, arguments); javaBridge.log(Array.prototype.join.call(arguments, ' ')); };
                    console.error = function() { origError.apply(console, arguments); javaBridge.error(Array.prototype.join.call(arguments, ' ')); };
                    """);
            } else if (newState == Worker.State.FAILED) {
                Throwable exc = webView.getEngine().getLoadWorker().getException();
                System.err.println("Load worker failed: " +
                    (exc != null ? exc.getMessage() : "unknown error"));
            }
        });

        // --- Navigation buttons ---------------------------------------------------
        Button backBtn = button("\u25C0", "Go back", () -> {
            var history = webView.getEngine().getHistory();
            if (history.getCurrentIndex() > 0) history.go(-1);
        });
        Button fwdBtn = button("\u25B6", "Go forward", () -> {
            var history = webView.getEngine().getHistory();
            if (history.getCurrentIndex() < history.getEntries().size() - 1) history.go(1);
        });
        Button reloadBtn = button("\u21BB", "Reload page", () -> {
            if (currentUrl != null) {
                webView.getEngine().reload();
            }
        });

        HBox toolbar = new HBox(8, backBtn, fwdBtn, reloadBtn, addressBar);
        toolbar.setPadding(new Insets(8));

        setTop(toolbar);
        setCenter(webView);
    }

    /**
     * Start the proxy server. Must be called before {@link #navigateTo(String)}.
     */
    public void start() {
        try {
            proxy.start();
            System.out.println("Proxy listening on 127.0.0.1:" + proxy.getListenPort());
        } catch (Exception e) {
            throw new RuntimeException("Failed to start proxy", e);
        }
    }

    /**
     * Stop the proxy server. Call this when the application exits.
     */
    public void stop() {
        proxy.stop();
    }

    /**
     * Returns the underlying JavaFX WebView.
     */
    public WebView getWebView() {
        return webView;
    }

    /**
     * Returns the address bar text field.
     */
    public TextField getAddressBar() {
        return addressBar;
    }

    /**
     * Returns the proxy server instance (useful for testing or advanced config).
     */
    public ProxyServer getProxy() {
        return proxy;
    }

    /** Navigate to the given URL through the proxy. */
    public void navigateTo(String rawUrl) {
        String url = normalizeUrl(rawUrl);
        if (url == null) {
            return;
        }
        addressBar.setText(url);
        currentUrl = url;
        String proxyUrl = rewriteThroughProxy(url);
        System.out.println("Navigating to: " + url + " (via proxy " + proxyUrl + ")");
        webView.getEngine().load(proxyUrl);
    }

    /** Rewrite a target URL so it goes through the local proxy. */
    private String rewriteThroughProxy(String url) {
        return url.replaceFirst("^https?://" + targetHost + ":" + targetPort,
            "http://127.0.0.1:" + proxy.getListenPort());
    }

    /** Adds https:// when the input has no scheme; returns null for blank input. */
    private static String normalizeUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String url = raw.trim();
        if (url.isEmpty()) {
            return null;
        }
        if (!url.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*") && !url.startsWith("about:")) {
            url = "https://" + url;
        }
        return url;
    }

    private static Button button(String label, String tooltip, Runnable action) {
        Button b = new Button(label);
        b.setTooltip(new Tooltip(tooltip));
        b.setOnAction(event -> action.run());
        return b;
    }
}
