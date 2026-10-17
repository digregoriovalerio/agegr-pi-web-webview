package com.example.webview;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.List;

/**
 * A simple browser window built on the JavaFX WebView.
 *
 * The URL can be provided in two ways:
 *  1. As a command-line argument:  java ... http://127.0.0.1:30141
 *  2. Typed into the address bar at runtime and confirmed with Enter.
 *
 * If no scheme is given (e.g. "127.0.0.1:30141"), "https://" is prepended.
 *
 * Navigation is routed through a local {@link ProxyServer} which works around
 * JavaFX WebView's inability to connect to certain localhost servers.
 */
public class WebViewApp extends Application {

    private static final String DEFAULT_URL = "http://127.0.0.1:30141";

    private ProxiedWebView view;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        view = new ProxiedWebView("127.0.0.1", 30141);
        view.start();

        Scene scene = new Scene(view, 1100, 750);
        stage.setTitle("Pi-Java");
        stage.setScene(scene);
        
        stage.show();

        List<String> args = getParameters().getRaw();
        String url = args.isEmpty() ? DEFAULT_URL : args.get(0);
        view.navigateTo(url);
    }

    @Override
    public void stop() {
        if (view != null) {
            view.stop();
        }
    }
}
