package com.nextstep.api.identity;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;
import com.nextstep.api.support.TestBrowser.Response;
import com.nextstep.api.support.TestMailbox;

class EmailVerificationTest extends IntegrationTestSupport {

    @Autowired
    AccountCleanup cleanup;

    @Autowired
    EmailVerificationGate gate;

    @Test
    void anUnconfirmedAccountCannotSignInAndLooksLikeAWrongPassword() {
        String email = uniqueEmail();
        TestBrowser registering = register(email, PASSWORD, "UTC");
        String confirmed = uniqueEmail();
        registerVerified(confirmed, PASSWORD, "UTC");

        // Otherwise registering an address and signing in with the chosen password would show whether the address
        // already had an account.
        Response early = loginAttempt(email, PASSWORD);
        Response wrongPassword = loginAttempt(confirmed, "not-the-password");
        assertThat(early.status()).isEqualTo(401);
        assertThat(early.code()).isEqualTo("INVALID_CREDENTIALS").isEqualTo(wrongPassword.code());
        assertThat(early.text("message")).isEqualTo(wrongPassword.text("message"));

        String token = TestMailbox.token(mailbox.await(email, "Confirm your email"));
        String stored = jdbc.queryForObject("""
                SELECT t.token_hash FROM account_tokens t JOIN accounts a ON a.id = t.account_id WHERE a.email = ?
                """, String.class, email);
        assertThat(stored).as("only a hash of the token is stored").hasSize(64).isNotEqualTo(token);

        assertThat(registering.post("/api/v1/auth/verify-email", map("token", token)).status()).isEqualTo(204);
        Response reused = registering.post("/api/v1/auth/verify-email", map("token", token));
        assertThat(reused.status()).isEqualTo(400);
        assertThat(reused.code()).as("links work once").isEqualTo("TOKEN_INVALID");

        TestBrowser signedIn = signIn(email, PASSWORD);
        assertThat(signedIn.get("/api/v1/me").json().get("emailVerified").asBoolean()).isTrue();
        assertThat(signedIn.get("/api/v1/applications").status()).isEqualTo(200);
    }

    @Test
    void registeringAgainBeforeConfirmingKeepsOnlyTheNewestDetails() {
        String email = uniqueEmail();
        register(email, "first-strong-passphrase", "UTC");
        String first = TestMailbox.token(mailbox.await(email, "Confirm your email"));
        TestBrowser again = register(email, "second-strong-passphrase", "Asia/Manila");
        String newest = TestMailbox.token(mailbox.await(email, "Confirm your email"));
        awaitOutboxEmpty(email);

        assertThat(again.post("/api/v1/auth/verify-email", map("token", first)).code())
                .as("the older link stopped working once the new one was sent").isEqualTo("TOKEN_INVALID");
        assertThat(again.post("/api/v1/auth/verify-email", map("token", newest)).status()).isEqualTo(204);
        assertThat(loginAttempt(email, "first-strong-passphrase").status()).isEqualTo(401);
        TestBrowser signedIn = signIn(email, "second-strong-passphrase");
        assertThat(signedIn.get("/api/v1/me").text("timeZone")).isEqualTo("Asia/Manila");
    }

    @Test
    void theGateStillRefusesASessionWhoseEmailIsNotConfirmed() throws Exception {
        // Sign-in refuses unconfirmed accounts, so no such session should ever exist. The gate is a second line of defense.
        String email = uniqueEmail();
        register(email, PASSWORD, "UTC");
        mailbox.await(email, "Confirm your email");
        UUID id = jdbc.queryForObject("SELECT id FROM accounts WHERE email = ?", UUID.class, email);
        AccountPrincipal principal = new AccountPrincipal(id, AccountKind.NORMAL, null, false);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        try {
            MockHttpServletResponse refused = new MockHttpServletResponse();
            assertThat(gate.preHandle(new MockHttpServletRequest("GET", "/api/v1/applications"), refused, null)).isFalse();
            assertThat(refused.getStatus()).isEqualTo(403);
            assertThat(refused.getContentAsString()).contains("EMAIL_NOT_VERIFIED");
            assertThat(gate.preHandle(new MockHttpServletRequest("PATCH", "/api/v1/me"), new MockHttpServletResponse(), null))
                    .isFalse();
            String[][] allowed = {{"GET", "/api/v1/me"}, {"DELETE", "/api/v1/me"}, {"GET", "/api/v1/me/export"},
                    {"POST", "/api/v1/auth/logout"}};
            for (String[] request : allowed) {
                assertThat(gate.preHandle(new MockHttpServletRequest(request[0], request[1]), new MockHttpServletResponse(), null))
                        .as(request[0] + " " + request[1]).isTrue();
            }
            jdbc.update("UPDATE accounts SET email_verified_at = now() WHERE id = ?", id);
            assertThat(gate.preHandle(new MockHttpServletRequest("GET", "/api/v1/applications"), new MockHttpServletResponse(), null))
                    .as("confirming takes effect at once").isTrue();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void onlyTheNewestLinkWorksAndLinksExpire() {
        String email = uniqueEmail();
        TestBrowser b = register(email, PASSWORD, "UTC");
        String first = TestMailbox.token(mailbox.await(email, "Confirm your email"));
        assertThat(b.post("/api/v1/auth/verify-email/resend", map("email", email)).status()).isEqualTo(202);
        String second = TestMailbox.token(mailbox.await(email, "Confirm your email"));
        awaitOutboxEmpty(email);
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
    void aFailedSendKeepsTheLinkAlreadyInTheInbox() {
        String email = uniqueEmail();
        TestBrowser b = register(email, PASSWORD, "UTC");
        String delivered = TestMailbox.token(mailbox.await(email, "Confirm your email"));
        awaitOutboxEmpty(email);
        mailbox.failFor(email);
        try {
            assertThat(b.post("/api/v1/auth/verify-email/resend", map("email", email)).status()).isEqualTo(202);
            awaitFailedAttempt(email);
            assertThat(b.post("/api/v1/auth/verify-email", map("token", delivered)).status())
                    .as("the replacement never arrived, so the delivered link still works").isEqualTo(204);
        } finally {
            mailbox.recover(email);
        }
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

    /** Waits until the queued email for the address has failed at least once. */
    private void awaitFailedAttempt(String email) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Integer failed = jdbc.queryForObject("""
                    SELECT count(*) FROM email_outbox o JOIN accounts a ON a.id = o.account_id
                    WHERE a.email = ? AND o.attempts > 0
                    """, Integer.class, email);
            if (failed != null && failed > 0) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        throw new AssertionError("The email to " + email + " was never attempted");
    }
}
