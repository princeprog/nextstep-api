package com.nextstep.api.identity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Have I Been Pwned's Pwned Passwords range API with k-anonymity: only the first five characters of the password's
 * SHA-1 hash leave the server, and padded responses hide which prefix matched. If the service is unreachable the
 * password is allowed (the length rule still applies), and a warning is logged without any password data. The whole
 * exchange, body included, must finish within the timeout, and responses over 256 KiB are refused (a padded answer is
 * about 40 KiB).
 */
public final class PwnedPasswordsChecker implements BreachedPasswordChecker {

    private static final Logger log = LoggerFactory.getLogger(PwnedPasswordsChecker.class);
    private static final long MAX_RESPONSE_BYTES = 256 * 1024;

    private final HttpClient http;
    private final URI base;
    private final Duration timeout;

    public PwnedPasswordsChecker(String baseUrl, Duration timeout) {
        this.base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public boolean isBreached(String password) {
        String hash = sha1Hex(password);
        String prefix = hash.substring(0, 5);
        String suffix = hash.substring(5);
        try {
            HttpRequest request = HttpRequest.newBuilder(base.resolve(prefix))
                    .timeout(timeout)
                    .header("Add-Padding", "true")
                    .header("User-Agent", "NextStep-API")
                    .GET()
                    .build();
            CompletableFuture<HttpResponse<String>> pending = http.sendAsync(request,
                    HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(), MAX_RESPONSE_BYTES));
            HttpResponse<String> response;
            try {
                response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                pending.cancel(true);
                log.warn("Breached-password check unavailable (TimeoutException); the password was allowed");
                return false;
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                log.warn("Breached-password check unavailable ({}); the password was allowed", cause.getClass().getSimpleName());
                return false;
            }
            if (response.statusCode() != 200) {
                log.warn("Breached-password check unavailable (HTTP {}); the password was allowed", response.statusCode());
                return false;
            }
            for (String line : response.body().split("\\r?\\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(suffix)) {
                    // Padding entries have a count of 0.
                    return parseCount(line.substring(colon + 1)) > 0;
                }
            }
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long parseCount(String value) {
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    static String sha1Hex(String password) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 is not available", ex);
        }
    }
}
