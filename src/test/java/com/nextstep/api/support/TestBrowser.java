package com.nextstep.api.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A minimal browser stand-in: keeps the session cookie, sends the CSRF header like the web app does,
 * and presents a distinct client address through X-Forwarded-For.
 */
public final class TestBrowser {

    public record Response(int status, String body, JsonNode json, HttpHeaders headers) {
        public String text(String field) {
            JsonNode node = json == null ? null : json.get(field);
            return node == null || node.isNull() ? null : node.asString();
        }

        public String code() {
            return text("code");
        }

        public String fieldError(String field) {
            JsonNode errors = json == null ? null : json.get("fieldErrors");
            JsonNode node = errors == null ? null : errors.get(field);
            return node == null ? null : node.asString();
        }

        public long total() {
            return json.get("total").asLong();
        }

        public List<String> ids(String arrayField) {
            List<String> ids = new ArrayList<>();
            JsonNode items = json.get(arrayField);
            for (int i = 0; i < items.size(); i++) {
                ids.add(items.get(i).get("id").asString());
            }
            return ids;
        }
    }

    public static final String COOKIE = "NEXTSTEP_SESSION";

    private final HttpClient http = HttpClient.newHttpClient();
    private final String base;
    private final ObjectMapper json;
    private final String ip;
    private final String cookieName;
    private final Map<String, String> extraHeaders = new LinkedHashMap<>();
    private String sessionCookie;
    private String csrfToken;
    private final List<String> setCookieHeaders = new ArrayList<>();

    public TestBrowser(int port, ObjectMapper json, String ip) {
        this(port, json, ip, COOKIE);
    }

    public TestBrowser(int port, ObjectMapper json, String ip, String cookieName) {
        this.base = "http://127.0.0.1:" + port;
        this.json = json;
        this.ip = ip;
        this.cookieName = cookieName;
    }

    /** Adds a header to every later request, for example X-Forwarded-Proto from a TLS-terminating proxy. */
    public TestBrowser header(String name, String value) {
        extraHeaders.put(name, value);
        return this;
    }

    public static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    public String sessionCookie() { return sessionCookie; }
    public void setSessionCookie(String value) { this.sessionCookie = value; }
    public String csrfToken() { return csrfToken; }
    public void setCsrfToken(String value) { this.csrfToken = value; }
    public List<String> setCookieHeaders() { return setCookieHeaders; }

    public void refreshCsrf() {
        Response response = get("/api/v1/auth/csrf");
        if (response.status() != 200) {
            throw new IllegalStateException("CSRF bootstrap failed: " + response.status() + " " + response.body());
        }
        csrfToken = response.text("token");
    }

    public Response get(String path) { return send("GET", path, null, true); }
    public Response post(String path, Object body) { return send("POST", path, body, true); }
    public Response patch(String path, Object body) { return send("PATCH", path, body, true); }
    public Response delete(String path) { return send("DELETE", path, null, true); }
    public Response delete(String path, Object body) { return send("DELETE", path, body, true); }

    public Response send(String method, String path, Object body, boolean withCsrf) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                    .header("X-Forwarded-For", ip)
                    .header("Accept", "application/json");
            extraHeaders.forEach(builder::header);
            HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
            if (body != null) {
                builder.header("Content-Type", "application/json");
                publisher = HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
            }
            if (sessionCookie != null) {
                builder.header("Cookie", cookieName + "=" + sessionCookie);
            }
            if (withCsrf && csrfToken != null) {
                builder.header("X-CSRF-TOKEN", csrfToken);
            }
            HttpResponse<String> response = http.send(builder.method(method, publisher).build(),
                    HttpResponse.BodyHandlers.ofString());
            for (String header : response.headers().allValues("set-cookie")) {
                setCookieHeaders.add(header);
                String pair = header.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).strip().equals(cookieName)) {
                    String value = pair.substring(eq + 1).strip();
                    boolean cleared = value.isEmpty() || header.toLowerCase().contains("max-age=0");
                    sessionCookie = cleared ? null : value;
                }
            }
            String text = response.body();
            boolean isJson = response.headers().firstValue("content-type").orElse("").contains("json");
            JsonNode tree = text == null || text.isBlank() || !isJson ? null : json.readTree(text);
            return new Response(response.statusCode(), text, tree, response.headers());
        } catch (IOException | InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

