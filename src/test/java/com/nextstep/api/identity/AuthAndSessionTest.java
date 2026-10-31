package com.nextstep.api.identity;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;
import com.nextstep.api.support.TestBrowser.Response;

class AuthAndSessionTest extends IntegrationTestSupport {

    @Test
    void registerSignOutAndSignBackIn() {
        TestBrowser b = browser();
        b.refreshCsrf();
        String anonymousSession = b.sessionCookie();
        String email = uniqueEmail();

        Response registered = b.post("/api/v1/auth/register", map(
                "displayName", "  Ana Cruz ", "email", "  " + email.toUpperCase() + " ",
                "password", "correct horse battery", "timeZone", "Asia/Manila"));
        assertThat(registered.status()).isEqualTo(201);
        assertThat(registered.text("email")).isEqualTo(email);
        assertThat(registered.text("displayName")).isEqualTo("Ana Cruz");
        assertThat(registered.text("kind")).isEqualTo("NORMAL");
        assertThat(registered.body()).doesNotContainIgnoringCase("password");
        assertThat(b.sessionCookie()).as("session ID rotates on authentication").isNotEqualTo(anonymousSession);

        Response stale = b.patch("/api/v1/me", map("displayName", "Ana"));
        assertThat(stale.status()).as("pre-login CSRF token is replaced").isEqualTo(403);
        assertThat(stale.code()).isEqualTo("CSRF_INVALID");

        b.refreshCsrf();
        assertThat(b.patch("/api/v1/me", map("displayName", "Ana")).status()).isEqualTo(200);
        String authenticatedSession = b.sessionCookie();

        assertThat(b.post("/api/v1/auth/logout", null).status()).isEqualTo(204);
        assertThat(b.get("/api/v1/me").status()).isEqualTo(401);

        TestBrowser replay = browser();
        replay.setSessionCookie(authenticatedSession);
        assertThat(replay.get("/api/v1/me").status()).as("ended session cannot be reused").isEqualTo(401);

        b.refreshCsrf();
        Response wrongPassword = b.post("/api/v1/auth/login", map("email", email, "password", "not the password"));
        Response unknownEmail = b.post("/api/v1/auth/login", map("email", uniqueEmail(), "password", "not the password"));
        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(unknownEmail.status()).isEqualTo(401);
        assertThat(wrongPassword.text("message")).isEqualTo(unknownEmail.text("message"));

        Response login = b.post("/api/v1/auth/login", map("email", email.toUpperCase(), "password", "correct horse battery"));
        assertThat(login.status()).isEqualTo(200);
        assertThat(b.get("/api/v1/me").text("displayName")).isEqualTo("Ana");
    }

    @Test
    void sessionCookieIsHttpOnlyLaxAndStoredInDatabase() {
        TestBrowser b = signedInUser();
        String cookie = b.setCookieHeaders().stream()
                .filter(h -> h.startsWith(TestBrowser.COOKIE + "="))
                .reduce((first, second) -> second).orElseThrow();
        assertThat(cookie).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");
        assertThat(cookie).as("Secure is enabled through COOKIE_SECURE for HTTPS deployments").doesNotContain("Secure");

        String accountId = b.get("/api/v1/me").text("id");
        Integer stored = jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?", Integer.class, accountId);
        assertThat(stored).isEqualTo(1);
    }

    @Test
    void unsafeRequestsWithoutCsrfAreRejected() {
        TestBrowser b = browser();
        b.refreshCsrf();
        b.setCsrfToken(null);
        for (String path : new String[] {"/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/demo"}) {
            Response response = b.post(path, map("email", uniqueEmail()));
            assertThat(response.status()).as(path).isEqualTo(403);
            assertThat(response.code()).isEqualTo("CSRF_INVALID");
        }
        TestBrowser user = signedInUser();
        Response forged = user.send("POST", "/api/v1/applications", map("company", "X", "jobTitle", "Y"), false);
        assertThat(forged.status()).isEqualTo(403);
        user.setCsrfToken("forged-token");
        assertThat(user.post("/api/v1/auth/logout", null).status()).isEqualTo(403);
    }

    @Test
    void duplicateNormalizedEmailIsRejected() {
        String email = uniqueEmail();
        TestBrowser first = browser();
        first.refreshCsrf();
        assertThat(first.post("/api/v1/auth/register", map("displayName", "A", "email", email,
                "password", "a-strong-passphrase", "timeZone", "UTC")).status()).isEqualTo(201);

        TestBrowser second = browser();
        second.refreshCsrf();
        Response duplicate = second.post("/api/v1/auth/register", map("displayName", "B", "email", " " + email.toUpperCase(),
                "password", "a-strong-passphrase", "timeZone", "UTC"));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.fieldError("email")).isNotBlank();
    }

    @Test
    void registrationValidatesFields() {
        TestBrowser b = browser();
        b.refreshCsrf();
        Response response = b.post("/api/v1/auth/register", map("displayName", "   ", "email", "not-an-email",
                "password", "short", "timeZone", "Mars/Olympus"));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.fieldError("displayName")).isNotBlank();
        assertThat(response.fieldError("email")).isNotBlank();
        assertThat(response.fieldError("password")).isNotBlank();
        assertThat(response.fieldError("timeZone")).isNotBlank();
        assertThat(b.get("/api/v1/me").status()).isEqualTo(401);
    }

    @Test
    void expiredSessionLosesAccess() {
        TestBrowser b = signedInUser();
        String accountId = b.get("/api/v1/me").text("id");
        jdbc.update("UPDATE spring_session SET last_access_time = 0, expiry_time = 0 WHERE principal_name = ?", accountId);
        Response me = b.get("/api/v1/me");
        assertThat(me.status()).isEqualTo(401);
        assertThat(me.code()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void accountDeletionRemovesDataAndEverySession() {
        String email = uniqueEmail();
        TestBrowser first = browser();
        first.refreshCsrf();
        first.post("/api/v1/auth/register", map("displayName", "A", "email", email, "password", "a-strong-passphrase", "timeZone", "UTC"));
        first.refreshCsrf();
        String accountId = first.get("/api/v1/me").text("id");
        String applicationId = createApplication(first);
        createTask(first, applicationId, "2030-01-01", null, "UTC");

        TestBrowser second = browser();
        second.refreshCsrf();
        assertThat(second.post("/api/v1/auth/login", map("email", email, "password", "a-strong-passphrase")).status()).isEqualTo(200);

        assertThat(first.delete("/api/v1/me").status()).isEqualTo(204);
        assertThat(first.get("/api/v1/me").status()).isEqualTo(401);
        assertThat(second.get("/api/v1/me").status()).as("other sessions are invalidated").isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM applications WHERE owner_id = CAST(? AS uuid)", Integer.class, accountId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tasks WHERE owner_id = CAST(? AS uuid)", Integer.class, accountId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?", Integer.class, accountId)).isZero();

        second.refreshCsrf();
        assertThat(second.post("/api/v1/auth/login", map("email", email, "password", "a-strong-passphrase")).status()).isEqualTo(401);
    }

    @Test
    void signedInUsersCannotStartAnotherWorkspace() {
        TestBrowser b = signedInUser();
        assertThat(b.post("/api/v1/auth/demo", null).code()).isEqualTo("ALREADY_SIGNED_IN");
        assertThat(b.post("/api/v1/auth/register", map("displayName", "X", "email", uniqueEmail(),
                "password", "a-strong-passphrase", "timeZone", "UTC")).status()).isEqualTo(409);
    }

    @Test
    void repeatedFailedLoginsAreRateLimited() {
        TestBrowser b = browser();
        b.refreshCsrf();
        String email = uniqueEmail();
        int lastStatus = 0;
        for (int i = 0; i < 11; i++) {
            lastStatus = b.post("/api/v1/auth/login", map("email", email, "password", "wrong-password-" + i)).status();
        }
        assertThat(lastStatus).isEqualTo(429);
    }

    @Test
    void privateEndpointsRequireSession() {
        TestBrowser anonymous = browser();
        for (String path : new String[] {"/api/v1/me", "/api/v1/applications", "/api/v1/tasks", "/api/v1/interviews", "/api/v1/dashboard"}) {
            assertThat(anonymous.get(path).status()).as(path).isEqualTo(401);
        }
    }
}

