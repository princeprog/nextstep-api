package com.nextstep.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class PwnedPasswordsCheckerTest {

    private static final String PASSWORD = "hunter2-but-much-longer";

    @Test
    void sendsOnlyTheHashPrefixAndMatchesTheSuffix() throws IOException {
        String hash = PwnedPasswordsChecker.sha1Hex(PASSWORD);
        String prefix = hash.substring(0, 5);
        String suffix = hash.substring(5);
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = serve(200, "00000000000000000000000000000000000:0\r\n" + suffix + ":42\r\n", requests);
        try {
            PwnedPasswordsChecker checker = checker(server);
            assertThat(checker.isBreached(PASSWORD)).isTrue();
            assertThat(checker.isBreached("a-password-nobody-has-used")).isFalse();
            assertThat(requests.getFirst()).isEqualTo("/range/" + prefix + " padding=true");
            assertThat(requests).noneMatch(request -> request.contains(suffix));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void paddingEntriesDoNotCount() throws IOException {
        String suffix = PwnedPasswordsChecker.sha1Hex(PASSWORD).substring(5);
        HttpServer server = serve(200, suffix + ":0\r\n", new CopyOnWriteArrayList<>());
        try {
            assertThat(checker(server).isBreached(PASSWORD)).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anUnavailableServiceAllowsThePassword() throws IOException {
        HttpServer server = serve(503, "busy", new CopyOnWriteArrayList<>());
        try {
            assertThat(checker(server).isBreached(PASSWORD)).isFalse();
        } finally {
            server.stop(0);
        }
        PwnedPasswordsChecker unreachable = new PwnedPasswordsChecker("http://127.0.0.1:9/range/", Duration.ofMillis(500));
        assertThat(unreachable.isBreached(PASSWORD)).isFalse();
    }

    private static PwnedPasswordsChecker checker(HttpServer server) {
        return new PwnedPasswordsChecker("http://127.0.0.1:" + server.getAddress().getPort() + "/range/", Duration.ofSeconds(2));
    }

    private static HttpServer serve(int status, String body, List<String> requests) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/range/", exchange -> {
            requests.add(exchange.getRequestURI().getPath() + " padding=" + exchange.getRequestHeaders().getFirst("Add-Padding"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
