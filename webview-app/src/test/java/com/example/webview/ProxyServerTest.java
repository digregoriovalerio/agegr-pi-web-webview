package com.example.webview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProxyServerTest {

    @Test
    void isValidRequestPathAcceptsNormalPaths() {
        assertTrue(ProxyServer.isValidRequestPath("/"));
        assertTrue(ProxyServer.isValidRequestPath("/api/agent/1/events"));
        assertTrue(ProxyServer.isValidRequestPath("/api/x;y"));
    }

    @Test
    void isValidRequestPathRejectsNullAndEmpty() {
        assertFalse(ProxyServer.isValidRequestPath(null));
        assertFalse(ProxyServer.isValidRequestPath(""));
    }

    @Test
    void isValidRequestPathRejectsAbsoluteFormUri() {
        assertFalse(ProxyServer.isValidRequestPath("http://evil.com/"));
        assertFalse(ProxyServer.isValidRequestPath("https://evil.com/x"));
    }

    @Test
    void isValidRequestPathRejectsPathTraversal() {
        assertFalse(ProxyServer.isValidRequestPath("/../etc/passwd"));
        assertFalse(ProxyServer.isValidRequestPath("/api/%2e%2e/secret"));
        assertFalse(ProxyServer.isValidRequestPath("/api/%2E%2E/secret"));
    }

    @Test
    void tryDechunkSingleChunk() throws Exception {
        byte[] input = "5\r\nhello\r\n0\r\n\r\n".getBytes("UTF-8");
        assertEquals("hello", new String(ProxyServer.tryDechunk(input, 1024), "UTF-8"));
    }

    @Test
    void tryDechunkMultipleChunks() throws Exception {
        byte[] input = "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n".getBytes("UTF-8");
        assertEquals("hello world", new String(ProxyServer.tryDechunk(input, 1024), "UTF-8"));
    }

    @Test
    void tryDechunkIncompleteReturnsNull() throws Exception {
        byte[] input = "5\r\nhel".getBytes("UTF-8");
        assertNull(ProxyServer.tryDechunk(input, 1024));
    }

    @Test
    void tryDechunkRejectsOversized() throws Exception {
        byte[] input = ("5\r\n" + "hello".repeat(100) + "\r\n0\r\n\r\n").getBytes("UTF-8");
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
            () -> ProxyServer.tryDechunk(input, 10));
    }
}
