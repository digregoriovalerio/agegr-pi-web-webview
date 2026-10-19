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
 * Main application entry point and GUI shell.
 *
 * <h2>What this class does</h2>
 * {@code WebViewApp} is the JavaFX {@link Application} that builds the user
 * interface. It is <b>GUI-only</b>: it creates the window, address bar, and
 * navigation buttons, then hands off all networking, proxying, JS bridging,
 * and SSE streaming to {@link ProxiedWebView}.
 *
 * <h2>Architecture role</h2>
 * {@code WebViewApp} owns a {@link ProxiedWebView} instance and places it in
 * the center of a {@link BorderPane}. A toolbar above contains back/forward/
 * reload buttons, an address bar, and a "Go" button.
 *
 * <h2>User flow</h2>
 * <ol>
 *   <li>The app starts {@code ProxiedWebView}, which launches a local HTTP
 *       proxy on a random port.</li>
 *   <li>The app navigates to {@link AppConfig#TARGET_ORIGIN} (pi-web) through
 *       that proxy.</li>
 *   <li>When the page finishes loading, the JS bridge is installed and (if
 *       enabled) AutoHello automatically types "Hello" into the chat input and
 *       submits it.</li>
 *   <li>The user can also type any URL in the address bar and press Enter or
 *       click "Go" to navigate.</li>
 * </ol>
 *
 * <h2>Communication edges logged</h2>
 * Every directional interaction is logged via {@link AppLogger#comm} or
 * {@link AppLogger#commTrace}:
 * <ul>
 *   <li>{@code User -> WebViewApp} — address bar / button clicks</li>
 *   <li>{@code WebViewApp -> ProxiedWebView} — navigate calls</li>
 *   <li>{@code WebViewApp -> Page JS} — address bar sync via location listener</li>
 * </ul>
 *
 * <h2>No networking here</h2>
 * This class never opens sockets, never starts a proxy, never handles SSE,
 * and never touches the JS bridge directly. All of that lives in
 * {@link ProxiedWebView}, {@link ProxyServer}, {@link JSBridge},
 * {@link SSEHandler}, and {@link AutoHello}.
 *
 * @see ProxiedWebView
 * @see AppConfig
 * @see AppLogger
 */
public class WebViewApp extends Application {

    private static final AppLogger LOG = AppLogger.INSTANCE;

    private ProxiedWebView proxiedWebView;
    private TextField addressBar;

    /**
     * Application entry point. Logs startup and launches the JavaFX thread.
     *
     * @param args command-line arguments (unused by this application)
     */
    public static void main(String[] args) {
        LOG.info("WebViewApp", "Starting application");
        launch(args);
    }

    /**
     * Builds the JavaFX stage and scene, creates {@link ProxiedWebView},
     * wires up the address bar and navigation buttons, then navigates to
     * the target origin.
     *
     * @param stage the primary JavaFX stage
     */
    @Override
    public void start(Stage stage) {
        LOG.info("WebViewApp", "Initializing GUI");

        // Create the proxied WebView (GUI + proxy interposition)
        proxiedWebView = new ProxiedWebView(
            AppConfig.TARGET_ORIGIN,
            AppConfig.PROXY_HOST,
            AppConfig.BUFFER_SIZE,
            AppConfig.USER_AGENT,
            AppConfig.CONNECT_TIMEOUT,
            AppConfig.SSE_READ_TIMEOUT,
            AppConfig.MAX_REQUEST_BODY_SIZE
        );

        // Track page load completion
        proxiedWebView.setOnPageLoaded(() -> {
            if (AppConfig.AUTOHELLO_ENABLED) {
                LOG.info("WebViewApp", "AutoHello enabled, injecting script");
                String script = new AutoHello().script();
                proxiedWebView.executeScriptAfterLoad(script);
            } else {
                LOG.info("WebViewApp", "AutoHello disabled");
            }
        });

        // Start the proxy server
        proxiedWebView.start();

        // ---- Build the GUI ----
        addressBar = new TextField();
        addressBar.setPromptText("Enter a URL and press Enter (e.g. 127.0.0.1:30141)");
        addressBar.setText(AppConfig.TARGET_ORIGIN);
        HBox.setHgrow(addressBar, Priority.ALWAYS);
        addressBar.setOnAction(event -> {
            String url = addressBar.getText();
            LOG.comm("User", "WebViewApp", "ADDRESS BAR SUBMIT", url);
            proxiedWebView.navigateTo(url);
        });

        // Keep the address bar in sync when the user follows links inside the page
        proxiedWebView.getWebView().getEngine().locationProperty().addListener((obs, oldLoc, newLoc) -> {
            if (newLoc != null && (newLoc.startsWith("http://") || newLoc.startsWith("https://"))) {
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

        // Defer navigation so the WebView control finishes initializing
        Platform.runLater(() -> {
            LOG.comm("WebViewApp", "ProxiedWebView", "INITIAL NAVIGATE", AppConfig.TARGET_ORIGIN);
            proxiedWebView.navigateTo(AppConfig.TARGET_ORIGIN);
        });
    }

    /** Creates a toolbar button with label, tooltip, and action. */
    private static Button button(String label, String tooltip, Runnable action) {
        Button b = new Button(label);
        b.setTooltip(new Tooltip(tooltip));
        b.setOnAction(event -> action.run());
        return b;
    }

    /**
     * Application shutdown hook. Stops {@link ProxiedWebView} (which stops the
     * proxy server and all SSE streams).
     */
    @Override
    public void stop() {
        LOG.info("WebViewApp", "Application shutting down");
        if (proxiedWebView != null) {
            proxiedWebView.stop();
        }
    }
}
