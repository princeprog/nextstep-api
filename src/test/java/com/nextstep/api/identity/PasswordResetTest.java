package com.nextstep.api.identity;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;
import com.nextstep.api.support.TestBrowser.Response;
import com.nextstep.api.support.TestMailbox;

class PasswordResetTest extends IntegrationTestSupport {

    private static final String NEW_PASSWORD = "a-new-strong-passphrase";

    @Test
    void requestsLookTheSameForUnknownEmailsAndOnlyAccountsGetALink() {
        String known = uniqueEmail();
        registerVerified(known, PASSWORD, "UTC");
        String unknown = uniqueEmail();
        TestBrowser b = browser();
        b.refreshCsrf();
        Response forKnown = b.post("/api/v1/auth/password-reset", map("email", known));
        Response forUnknown = b.post("/api/v1/auth/password-reset", map("email", unknown));
        assertThat(forKnown.status()).isEqualTo(202);
        assertThat(forUnknown.status()).isEqualTo(202);
        assertThat(forKnown.body()).isEqualTo(forUnknown.body());
        assertThat(mailbox.await(known, "Reset your NextStep password").text()).contains("/reset-password#token=");
        mailbox.assertNothingFor(unknown, Duration.ofMillis(500));
    }

    @Test
    void resetLinkWorksOnceAndSignsOutEverySession() {
        String email = uniqueEmail();
        registerVerified(email, PASSWORD, "UTC");
        TestBrowser laptop = signIn(email, PASSWORD);
        TestBrowser phone = signIn(email, PASSWORD);
        TestBrowser b = browser();
        b.refreshCsrf();
        assertThat(b.post("/api/v1/auth/password-reset", map("email", email)).status()).isEqualTo(202);
        String token = TestMailbox.token(mailbox.await(email, "Reset your NextStep password"));

        Response weak = b.post("/api/v1/auth/password-reset/confirm", map("token", token, "password", "short"));
        assertThat(weak.status()).isEqualTo(422);
        assertThat(weak.fieldError("password")).isNotBlank();
        Response breached = b.post("/api/v1/auth/password-reset/confirm", map("token", token, "password", "a-breached-passphrase"));
        assertThat(breached.fieldError("password")).as("a rejected password leaves the link usable").contains("data breach");

        assertThat(b.post("/api/v1/auth/password-reset/confirm", map("token", token, "password", NEW_PASSWORD)).status())
                .isEqualTo(204);
        assertThat(laptop.get("/api/v1/me").status()).isEqualTo(401);
        assertThat(phone.get("/api/v1/me").status()).isEqualTo(401);

        b.refreshCsrf();
        assertThat(b.post("/api/v1/auth/password-reset/confirm", map("token", token, "password", "yet-another-passphrase")).code())
                .as("links work once").isEqualTo("TOKEN_INVALID");
        assertThat(b.post("/api/v1/auth/login", map("email", email, "password", PASSWORD)).status()).isEqualTo(401);
        signIn(email, NEW_PASSWORD);
        assertThat(mailbox.await(email, "password was changed")).isNotNull();
    }

    @Test
    void onlyTheNewestLinkWorksAndLinksExpireAfterThirtyMinutes() {
        String email = uniqueEmail();
        registerVerified(email, PASSWORD, "UTC");
        TestBrowser b = browser();
        b.refreshCsrf();
        b.post("/api/v1/auth/password-reset", map("email", email));
        String first = TestMailbox.token(mailbox.await(email, "Reset your NextStep password"));
        b.post("/api/v1/auth/password-reset", map("email", email));
        String second = TestMailbox.token(mailbox.await(email, "Reset your NextStep password"));
        awaitOutboxEmpty(email);
        assertThat(b.post("/api/v1/auth/password-reset/confirm", map("token", first, "password", NEW_PASSWORD)).code())
                .isEqualTo("TOKEN_INVALID");
        clock.advance(Duration.ofMinutes(31));
        assertThat(b.post("/api/v1/auth/password-reset/confirm", map("token", second, "password", NEW_PASSWORD)).code())
                .isEqualTo("TOKEN_INVALID");
        clock.reset();
        signIn(email, PASSWORD);
    }

    @Test
    void resetRequestsAreLimitedPerEmailWhetherOrNotItHasAnAccount() {
        String email = uniqueEmail();
        int last = 0;
        for (int i = 0; i < 4; i++) {
            TestBrowser b = browser();
            b.refreshCsrf();
            last = b.post("/api/v1/auth/password-reset", map("email", email)).status();
        }
        assertThat(last).as("three requests per email per hour").isEqualTo(429);
    }

    @Test
    void resettingAnUnverifiedAccountAlsoVerifiesIt() {
        String email = uniqueEmail();
        TestBrowser b = register(email, PASSWORD, "UTC");
        mailbox.await(email, "Confirm your email");
        b.post("/api/v1/auth/password-reset", map("email", email));
        String token = TestMailbox.token(mailbox.await(email, "Reset your NextStep password"));
        assertThat(b.post("/api/v1/auth/password-reset/confirm", map("token", token, "password", NEW_PASSWORD)).status())
                .isEqualTo(204);
        assertThat(signIn(email, NEW_PASSWORD).get("/api/v1/applications").status()).isEqualTo(200);
    }

    @Test
    void aDeadLinkIsReportedBeforeThePasswordRules() {
        TestBrowser b = browser();
        b.refreshCsrf();
        Response response = b.post("/api/v1/auth/password-reset/confirm", map("token", "x".repeat(43), "password", "short"));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).as("someone with an expired link learns it before choosing a password")
                .isEqualTo("TOKEN_INVALID");
    }

    @Test
    void aResetRetiresUnusedConfirmationLinks() {
        String email = uniqueEmail();
        TestBrowser b = register(email, PASSWORD, "UTC");
        String confirmation = TestMailbox.token(mailbox.await(email, "Confirm your email"));
        b.post("/api/v1/auth/password-reset", map("email", email));
        String reset = TestMailbox.token(mailbox.await(email, "Reset your NextStep password"));
        assertThat(b.post("/api/v1/auth/password-reset/confirm", map("token", reset, "password", NEW_PASSWORD)).status())
                .isEqualTo(204);
        b.refreshCsrf();
        assertThat(b.post("/api/v1/auth/verify-email", map("token", confirmation)).code()).isEqualTo("TOKEN_INVALID");
    }
}
