package com.nextstep.api.identity;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;
import com.nextstep.api.support.TestBrowser.Response;
import com.nextstep.api.support.TestMailbox;

class AuthAndSessionTest extends IntegrationTestSupport {

    @Test
    void registerConfirmSignOutAndSignBackIn() {
        TestBrowser b = browser();
        b.refreshCsrf();
        String email = uniqueEmail();

        Response registered = b.post("/api/v1/auth/register", map(
                "displayName", "  Ana Cruz ", "email", "  " + email.toUpperCase() + " ",
                "password", "correct horse battery", "timeZone", "Asia/Manila"));
        assertThat(registered.status()).isEqualTo(202);
        assertThat(registered.text("email")).isEqualTo(email);
        assertThat(registered.body()).doesNotContainIgnoringCase("password");
        assertThat(b.get("/api/v1/me").status()).as("registration doesn't start a session").isEqualTo(401);

        String token = TestMailbox.token(mailbox.await(email, "Confirm your email"));
        assertThat(b.post("/api/v1/auth/verify-email", map("token", token)).status()).isEqualTo(204);

        String anonymousSession = b.sessionCookie();
        Response login = b.post("/api/v1/auth/login", map("email", email.toUpperCase(), "password", "correct horse battery"));
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.text("displayName")).isEqualTo("Ana Cruz");
        assertThat(login.json().get("emailVerified").asBoolean()).isTrue();
        assertThat(b.sessionCookie()).as("session ID rotates on sign-in").isNotEqualTo(anonymousSession);

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

        assertThat(b.post("/api/v1/auth/login", map("email", email, "password", "correct horse battery")).status()).isEqualTo(200);
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
        for (String path : new String[] {"/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/demo",
                "/api/v1/auth/verify-email", "/api/v1/auth/verify-email/resend", "/api/v1/auth/password-reset",
                "/api/v1/auth/password-reset/confirm"}) {
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
    void registeringAnExistingEmailLooksTheSameAndOnlyTellsTheOwner() {
        String email = uniqueEmail();
        registerVerified(email, PASSWORD, "UTC");

        TestBrowser second = browser();
        second.refreshCsrf();
        Response duplicate = second.post("/api/v1/auth/register", map("displayName", "Someone", "email", " " + email.toUpperCase(),
                "password", "another-strong-passphrase", "timeZone", "UTC"));
        TestBrowser third = browser();
        third.refreshCsrf();
        String freshEmail = uniqueEmail();
        Response fresh = third.post("/api/v1/auth/register", map("displayName", "Someone", "email", freshEmail,
                "password", "another-strong-passphrase", "timeZone", "UTC"));

        assertThat(duplicate.status()).isEqualTo(202);
        assertThat(fresh.status()).isEqualTo(202);
        assertThat(duplicate.body().replace(email, "E")).isEqualTo(fresh.body().replace(freshEmail, "E"));
        assertThat(mailbox.await(email, "already have a NextStep account").text()).contains("/forgot-password");
        assertThat(mailbox.await(freshEmail, "Confirm your email")).isNotNull();

        signIn(email, PASSWORD);
        TestBrowser attacker = browser();
        attacker.refreshCsrf();
        assertThat(attacker.post("/api/v1/auth/login", map("email", email, "password", "another-strong-passphrase")).status())
                .as("the existing account keeps its password").isEqualTo(401);
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

        Response breached = b.post("/api/v1/auth/register", map("displayName", "A", "email", uniqueEmail(),
                "password", "a-breached-passphrase", "timeZone", "UTC"));
        assertThat(breached.status()).isEqualTo(422);
        assertThat(breached.fieldError("password")).contains("data breach");
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
        for (String path : new String[] {"/api/v1/me", "/api/v1/me/export", "/api/v1/applications", "/api/v1/tasks",
                "/api/v1/interviews", "/api/v1/dashboard"}) {
            assertThat(anonymous.get(path).status()).as(path).isEqualTo(401);
        }
    }
}
