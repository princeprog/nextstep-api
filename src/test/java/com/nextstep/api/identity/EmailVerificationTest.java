package com.nextstep.api.identity;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;
import com.nextstep.api.support.TestBrowser.Response;
import com.nextstep.api.support.TestMailbox;

class EmailVerificationTest extends IntegrationTestSupport {

    @Autowired
    AccountCleanup cleanup;

    @Test
    void confirmationLinkWorksOnceAndUnlocksTheWorkspaceEverywhere() {
        String email = uniqueEmail();
        TestBrowser registering = register(email, PASSWORD, "UTC");
        TestBrowser signedIn = signIn(email, PASSWORD);
        assertThat(signedIn.get("/api/v1/me").json().get("emailVerified").asBoolean()).isFalse();
        Response blocked = signedIn.get("/api/v1/applications");
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(blocked.code()).isEqualTo("EMAIL_NOT_VERIFIED");

        String token = TestMailbox.token(mailbox.await(email, "Confirm your email"));
        String accountId = signedIn.get("/api/v1/me").text("id");
        String stored = jdbc.queryForObject("SELECT token_hash FROM account_tokens WHERE account_id = CAST(? AS uuid)",
                String.class, accountId);
        assertThat(stored).as("only a hash of the token is stored").hasSize(64).isNotEqualTo(token);

        assertThat(registering.post("/api/v1/auth/verify-email", map("token", token)).status()).isEqualTo(204);
        Response reused = registering.post("/api/v1/auth/verify-email", map("token", token));
        assertThat(reused.status()).isEqualTo(400);
        assertThat(reused.code()).as("links work once").isEqualTo("TOKEN_INVALID");

        assertThat(signedIn.get("/api/v1/applications").status()).as("verifying elsewhere unlocks this session").isEqualTo(200);
        assertThat(signedIn.get("/api/v1/me").json().get("emailVerified").asBoolean()).isTrue();
    }

    @Test
    void unverifiedAccountsCanOnlyManageThemselves() {
        String email = uniqueEmail();
        register(email, PASSWORD, "UTC");
        mailbox.await(email, "Confirm your email");
        TestBrowser s = signIn(email, PASSWORD);
        assertThat(s.get("/api/v1/me").status()).isEqualTo(200);
        assertThat(s.get("/api/v1/me/export").status()).isEqualTo(200);
        for (String path : new String[] {"/api/v1/applications", "/api/v1/tasks", "/api/v1/interviews", "/api/v1/dashboard"}) {
            Response response = s.get(path);
            assertThat(response.status()).as(path).isEqualTo(403);
            assertThat(response.code()).isEqualTo("EMAIL_NOT_VERIFIED");
        }
        assertThat(s.patch("/api/v1/me", map("displayName", "X")).status()).isEqualTo(403);
        assertThat(s.post("/api/v1/me/password", map("currentPassword", PASSWORD, "newPassword", "another-strong-passphrase"))
                .status()).isEqualTo(403);
        assertThat(s.post("/api/v1/auth/verify-email/resend", map("email", email)).status()).isEqualTo(202);
        assertThat(mailbox.await(email, "Confirm your email")).isNotNull();
        assertThat(s.delete("/api/v1/me", map("password", PASSWORD)).status()).isEqualTo(204);
    }

    @Test
    void onlyTheNewestLinkWorksAndLinksExpire() {
        String email = uniqueEmail();
        TestBrowser b = register(email, PASSWORD, "UTC");
        String first = TestMailbox.token(mailbox.await(email, "Confirm your email"));
        assertThat(b.post("/api/v1/auth/verify-email/resend", map("email", email)).status()).isEqualTo(202);
        String second = TestMailbox.token(mailbox.await(email, "Confirm your email"));
        assertThat(second).isNotEqualTo(first);
        assertThat(b.post("/api/v1/auth/verify-email", map("token", first)).code()).isEqualTo("TOKEN_INVALID");

        clock.advance(Duration.ofHours(25));
        assertThat(b.post("/api/v1/auth/verify-email", map("token", second)).code()).as("expired after 24 hours")
                .isEqualTo("TOKEN_INVALID");
        clock.reset();

        assertThat(b.post("/api/v1/auth/verify-email/resend", map("email", email)).status()).isEqualTo(202);
        String third = TestMailbox.token(mailbox.await(email, "Confirm your email"));
        assertThat(b.post("/api/v1/auth/verify-email", map("token", third)).status()).isEqualTo(204);
    }

    @Test
    void malformedTokensAreRejectedWithoutDetail() {
        TestBrowser b = browser();
        b.refreshCsrf();
        for (String token : new String[] {"", "short", "x".repeat(43), "not a token at all"}) {
            Response response = b.post("/api/v1/auth/verify-email", map("token", token));
            assertThat(response.status()).as(token).isEqualTo(400);
            assertThat(response.code()).isEqualTo("TOKEN_INVALID");
        }
    }

    @Test
    void resendIsSilentForUnknownAndVerifiedEmails() {
        String unknown = uniqueEmail();
        String verified = uniqueEmail();
        registerVerified(verified, PASSWORD, "UTC");
        TestBrowser b = browser();
        b.refreshCsrf();
        Response forUnknown = b.post("/api/v1/auth/verify-email/resend", map("email", unknown));
        Response forVerified = b.post("/api/v1/auth/verify-email/resend", map("email", verified));
        assertThat(forUnknown.status()).isEqualTo(202);
        assertThat(forVerified.status()).isEqualTo(202);
        assertThat(forUnknown.body()).isEqualTo(forVerified.body());
        mailbox.assertNothingFor(unknown, Duration.ofMillis(500));
        mailbox.assertNothingFor(verified, Duration.ofMillis(100));
    }

    @Test
    void neverVerifiedAccountsAreRemovedAfterAWeek() {
        String stale = uniqueEmail();
        register(stale, PASSWORD, "UTC");
        mailbox.await(stale, "Confirm your email");
        String kept = uniqueEmail();
        registerVerified(kept, PASSWORD, "UTC");
        String staleId = jdbc.queryForObject("SELECT id::text FROM accounts WHERE email = ?", String.class, stale);

        clock.advance(Duration.ofDays(8));
        assertThat(cleanup.removeUnverified()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE email = ?", Integer.class, stale)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE email = ?", Integer.class, kept)).isOne();
        assertThat(jdbc.queryForObject("SELECT reason FROM account_deletions WHERE account_id = CAST(? AS uuid)",
                String.class, staleId)).isEqualTo("UNVERIFIED");
    }

    @Test
    void registrationIsLimitedPerEmail() {
        String email = uniqueEmail();
        int last = 0;
        for (int i = 0; i < 6; i++) {
            TestBrowser b = browser();
            b.refreshCsrf();
            last = b.post("/api/v1/auth/register", map("displayName", "A", "email", email, "password", PASSWORD,
                    "timeZone", "UTC")).status();
        }
        assertThat(last).as("five registrations per email per hour").isEqualTo(429);
    }
}
