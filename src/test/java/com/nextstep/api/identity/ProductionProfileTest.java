package com.nextstep.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpHeaders;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;
import com.nextstep.api.support.TestBrowser.Response;

/** Runs the API with the production profile and checks the cookie and headers a browser would get. */
@ActiveProfiles("production")
@TestPropertySource(properties = "nextstep.app-public-origin=https://app.nextstep.example")
class ProductionProfileTest extends IntegrationTestSupport {

    private static final String COOKIE = "__Host-NEXTSTEP_SESSION";

    @Override
    protected String sessionCookieName() {
        return COOKIE;
    }

    @Test
    void sessionCookieIsHostOnlySecureHttpOnlyAndLax() {
        TestBrowser b = signedInUser();
        String cookie = b.setCookieHeaders().stream()
                .filter(header -> header.startsWith(COOKIE + "="))
                .reduce((first, second) -> second).orElseThrow();
        assertThat(cookie).contains("Secure").contains("HttpOnly").contains("SameSite=Lax").contains("Path=/")
                .doesNotContainIgnoringCase("Domain=");
        assertThat(b.get("/api/v1/me").status()).isEqualTo(200);
    }

    @Test
    void responsesBehindTheHttpsProxyCarrySecurityHeaders() {
        TestBrowser b = browser().header("X-Forwarded-Proto", "https");
        HttpHeaders headers = b.get("/api/v1/auth/csrf").headers();
        assertThat(headers.firstValue("strict-transport-security")).hasValueSatisfying(value ->
                assertThat(value).contains("max-age=31536000").contains("includeSubDomains"));
        assertThat(headers.firstValue("content-security-policy")).hasValue("default-src 'none'; frame-ancestors 'none'");
        assertThat(headers.firstValue("x-content-type-options")).hasValue("nosniff");
        assertThat(headers.firstValue("x-frame-options")).hasValue("DENY");
        assertThat(headers.firstValue("cache-control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
    }

    @Test
    void healthIsPublicAndReportsOnlyItsStatus() {
        Response health = browser().get("/api/v1/health");
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.body()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(health.headers().firstValue("cache-control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
    }
}
