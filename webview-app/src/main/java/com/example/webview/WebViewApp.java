package com.example.webview;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.util.List;

/**
 * A simple browser window built on the JavaFX WebView.
 *
 * Navigation is routed through a local {@link ProxyServer} which works around
 * JavaFX WebView's inability to connect to certain localhost servers
 * (e.g. pi-web on 127.0.0.1:30141). The proxy rewrites Origin/Referer headers
 * and injects a browser-like User-Agent so that server-side origin checks pass.
 *
 * The URL can be provided in two ways:
 *  1. As a command-line argument:  java ... http://127.0.0.1:30141
 *  2. Typed into the address bar at runtime and confirmed with Enter.
 *
 * If no scheme is given (e.g. "127.0.0.1:30141"), "https://" is prepended.
 */
public class WebViewApp extends Application {

    private static final String DEFAULT_URL = "http://127.0.0.1:30141";

    private WebView webView;
    private TextField addressBar;
    private String currentUrl;
    private ProxyServer proxy;
    private int proxyPort;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        webView = new WebView();

        // --- Address bar ---------------------------------------------------------
        addressBar = new TextField();
        addressBar.setPromptText("Enter a URL and press Enter (e.g. 127.0.0.1:30141)");
        HBox.setHgrow(addressBar, Priority.ALWAYS);
        addressBar.setOnAction(event -> navigateTo(addressBar.getText()));

        // Keep the address bar in sync when the user follows links inside the page
        webView.getEngine().locationProperty().addListener((obs, oldLoc, newLoc) -> {
            if (newLoc != null && newLoc.matches("^https?://") && !newLoc.equals(currentUrl)) {
                addressBar.setText(newLoc);
                currentUrl = newLoc;
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
                navigateTo(currentUrl);
            }
        });

        HBox toolbar = new HBox(8, backBtn, fwdBtn, reloadBtn, addressBar);
        toolbar.setPadding(new Insets(8));

        BorderPane root = new BorderPane();
        root.setTop(toolbar);
        root.setCenter(webView);

        // --- Start local proxy ----------------------------------------------------
        startProxy();

        // --- Determine the URL from the command line (input), or use a default ---
        List<String> args = getParameters().getRaw();
        String url = args.isEmpty() ? DEFAULT_URL : args.get(0);
        addressBar.setText(normalizeUrl(url));

        Scene scene = new Scene(root, 1100, 750);
        stage.setTitle("JavaFX WebView (proxied)");
        stage.setScene(scene);
        stage.show();

        navigateTo(url);
    }

    @Override
    public void stop() {
        if (proxy != null) {
            proxy.stop();
        }
    }

    /** Start the local HTTP proxy. */
    private void startProxy() {
        try {
            proxy = new ProxyServer("127.0.0.1", 30141, 0);
            proxy.start();
            proxyPort = proxy.getListenPort();
            System.out.println("Proxy listening on 127.0.0.1:" + proxyPort);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start proxy", e);
        }
    }

    /** Navigate to the given URL through the proxy. */
    private void navigateTo(String rawUrl) {
        String url = normalizeUrl(rawUrl);
        if (url == null) {
            return;
        }
        // Extract target host:port from the URL for the proxy
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url);
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid URL: " + url);
            return;
        }
        String host = uri.getHost();
        int port = uri.getPort() != -1 ? uri.getPort() : (uri.getScheme().equals("https") ? 443 : 80);
        proxy.setTarget(host, port);

        addressBar.setText(url);
        currentUrl = url;
        String proxyUrl = rewriteThroughProxy(url);
        System.out.println("Navigating to: " + url + " (via proxy " + proxyUrl + ")");
        webView.getEngine().load(proxyUrl);
    }

    /** Rewrite a target URL so it goes through the local proxy. */
    private String rewriteThroughProxy(String url) {
        return url.replaceFirst("^https?://127\\.0\\.0\\.1:\\d+",
            "http://127.0.0.1:" + proxyPort);
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
