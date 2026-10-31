package com.nextstep.api.common;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;
import com.nextstep.api.support.TestBrowser.Response;

class RequestLimitsTest extends IntegrationTestSupport {

    @Test
    void largestLegitimateBodyIsAccepted() {
        TestBrowser b = signedInUser();
        Response response = b.post("/api/v1/applications", map("company", "Acme", "jobTitle", "Engineer",
                "notes", "\u00e9".repeat(10_000)));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
    }

    @Test
    void oversizedBodyIsRejectedFromItsDeclaredLength() {
        TestBrowser b = signedInUser();
        Response response = b.post("/api/v1/applications", map("company", "Acme", "jobTitle", "Engineer",
                "notes", "x".repeat(300_000)));
        assertThat(response.status()).isEqualTo(413);
        assertThat(response.code()).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void oversizedChunkedBodyIsRejectedWhileReading() throws Exception {
        TestBrowser b = signedInUser();
        byte[] body = ("{\"company\":\"Acme\",\"jobTitle\":\"Engineer\",\"notes\":\"" + "x".repeat(300_000) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/applications"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Cookie", TestBrowser.COOKIE + "=" + b.sessionCookie())
                .header("X-CSRF-TOKEN", b.csrfToken())
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(413);
        assertThat(response.body()).contains("PAYLOAD_TOO_LARGE");
    }
}
