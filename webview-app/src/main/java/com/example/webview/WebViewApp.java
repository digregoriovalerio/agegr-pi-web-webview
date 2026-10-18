package com.example.webview;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.List;

/**
 * Main application entry point using the {@link ProxiedWebView} component.
 */
public class WebViewApp extends Application {

    private static final String DEFAULT_URL = "http://127.0.0.1:30141";

    private ProxiedWebView proxiedWebView;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        // Use the ProxiedWebView component which encapsulates WebView + toolbar + proxy
        proxiedWebView = new ProxiedWebView("127.0.0.1", 30141);

        // Start the proxy server
        proxiedWebView.start();

        // Determine the URL from command line or use default
        List<String> args = getParameters().getRaw();
        String url = args.isEmpty() ? DEFAULT_URL : args.get(0);
        proxiedWebView.getAddressBar().setText(url);

        Scene scene = new Scene(proxiedWebView, 1100, 750);
        stage.setTitle("JavaFX WebView (proxied)");
        stage.setScene(scene);
        stage.show();

        // Defer navigation so the WebView2 control (Windows) finishes initializing
        Platform.runLater(() -> proxiedWebView.navigateTo(url));
    }

    @Override
    public void stop() {
        if (proxiedWebView != null) {
            proxiedWebView.stop();
        }
    }
}