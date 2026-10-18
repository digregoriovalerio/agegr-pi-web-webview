package com.example.webview;

import com.example.webview.logging.AppLogger;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;

/**
 * Main application entry point.
 *
 * WebViewApp is responsible ONLY for GUI logic:
 *   - Creating the JavaFX stage and scene
 *   - Creating the ProxiedWebView
 *   - Address bar handling (text field + navigation)
 *   - Navigation buttons (back, forward, reload)
 *
 * All network, proxy, JS bridging, SSE, and auto-hello logic lives in
 * ProxiedWebView / ProxyServer / JSBridge / SSEHandler / AutoHello respectively.
 * SSE messages (__PI_SSE_START__ / __PI_SSE_STOP__) are handled internally by
 * ProxiedWebView -> SSEHandler, so WebViewApp does not need a message callback.
 *
 * Flow:
 *   1. User enters URL in address bar and presses Enter
 *   2. WebViewApp calls ProxiedWebView.navigateTo(url)
 *   3. ProxiedWebView rewrites URL through ProxyServer and loads it
 *   4. Page loads, JS bridge installed, EventSource polyfill active
 *   5. AutoHello (dev only) may submit "Hello" automatically
 *   6. SSE messages flow: Page -> JSBridge -> ProxiedWebView -> SSEHandler -> Target
 *   7. SSE events flow back: Target -> SSEHandler -> ProxiedWebView -> Page JS
 */
public class WebViewApp extends Application {

    private static final AppLogger LOG = AppLogger.INSTANCE;

    private static final String DEFAULT_URL = "http://127.0.0.1:30141";

    private ProxiedWebView proxiedWebView;
    private TextField addressBar;

    public static void main(String[] args) {
        LOG.info("WebViewApp", "Starting application");
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        LOG.info("WebViewApp", "Initializing GUI");

        // Create the proxied WebView (GUI + proxy interposition)
        proxiedWebView = new ProxiedWebView("127.0.0.1", 30141);

        // Track page load completion
        proxiedWebView.setOnPageLoaded(() -> {
            LOG.info("WebViewApp", "Page load completed callback fired");
        });

        // Start the proxy server
        proxiedWebView.start();

        // ---- Build the GUI ----
        addressBar = new TextField();
        addressBar.setPromptText("Enter a URL and press Enter (e.g. 127.0.0.1:30141)");
        addressBar.setText(DEFAULT_URL);
        HBox.setHgrow(addressBar, Priority.ALWAYS);
        addressBar.setOnAction(event -> {
            String url = addressBar.getText();
            LOG.comm("User", "WebViewApp", "ADDRESS BAR SUBMIT", url);
            proxiedWebView.navigateTo(url);
        });

        // Keep the address bar in sync when the user follows links inside the page
        proxiedWebView.getWebView().getEngine().locationProperty().addListener((obs, oldLoc, newLoc) -> {
            if (newLoc != null && newLoc.matches("^https?://")) {
                Platform.runLater(() -> addressBar.setText(newLoc));
            }
        });

        Button goButton = button("Go", "Navigate to the URL in the address bar", () -> {
            String url = addressBar.getText();
            LOG.comm("User", "WebViewApp", "GO BUTTON", url);
            proxiedWebView.navigateTo(url);
        });

        Button backBtn = button("\u25C0", "Go back", () -> {
            var history = proxiedWebView.getWebView().getEngine().getHistory();
            if (history.getCurrentIndex() > 0) history.go(-1);
        });
        Button fwdBtn = button("\u25B6", "Go forward", () -> {
            var history = proxiedWebView.getWebView().getEngine().getHistory();
            if (history.getCurrentIndex() < history.getEntries().size() - 1) history.go(1);
        });
        Button reloadBtn = button("\u21BB", "Reload page", () -> {
            if (proxiedWebView.getWebView().getEngine().getLocation() != null) {
                proxiedWebView.getWebView().getEngine().reload();
            }
        });

        HBox toolbar = new HBox(8, backBtn, fwdBtn, reloadBtn, addressBar, goButton);
        toolbar.setPadding(new Insets(8));

        BorderPane root = new BorderPane();
        root.setTop(toolbar);
        root.setCenter(proxiedWebView);

        Scene scene = new Scene(root, 1100, 750);
        stage.setTitle("JavaFX WebView (proxied)");
        stage.setScene(scene);
        stage.show();

        // Defer navigation so the WebView2 control (Windows) finishes initializing
        Platform.runLater(() -> {
            LOG.comm("WebViewApp", "ProxiedWebView", "INITIAL NAVIGATE", DEFAULT_URL);
            proxiedWebView.navigateTo(DEFAULT_URL);
        });
    }

    private static Button button(String label, String tooltip, Runnable action) {
        Button b = new Button(label);
        b.setTooltip(new Tooltip(tooltip));
        b.setOnAction(event -> action.run());
        return b;
    }

    @Override
    public void stop() {
        LOG.info("WebViewApp", "Application shutting down");
        if (proxiedWebView != null) {
            proxiedWebView.stop();
        }
    }
}
