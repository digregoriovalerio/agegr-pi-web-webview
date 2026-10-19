package com.example.webview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ProxiedWebViewTest {

    @Test
    void normalizeUrlAddsHttpWhenNoScheme() {
        assertEquals("http://127.0.0.1:30141", ProxiedWebView.normalizeUrl("127.0.0.1:30141"));
        assertEquals("http://localhost/", ProxiedWebView.normalizeUrl("localhost/"));
    }

    @Test
    void normalizeUrlKeepsExistingScheme() {
        assertEquals("http://example.com/", ProxiedWebView.normalizeUrl("http://example.com/"));
        assertEquals("https://example.com/", ProxiedWebView.normalizeUrl("https://example.com/"));
    }

    @Test
    void normalizeUrlKeepsAboutBlank() {
        assertEquals("about:blank", ProxiedWebView.normalizeUrl("about:blank"));
    }

    @Test
    void normalizeUrlRejectsBlank() {
        assertNull(ProxiedWebView.normalizeUrl(null));
        assertNull(ProxiedWebView.normalizeUrl(""));
        assertNull(ProxiedWebView.normalizeUrl("   "));
    }
}
