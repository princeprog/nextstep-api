package com.nextstep.api.identity;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;

class EmailOutboxTest extends IntegrationTestSupport {

    @Autowired
    AccountEmails emails;

    @Test
    void failedSendsAreRetriedLaterAndTheOutboxHoldsNoLink() {
        String email = uniqueEmail();
        registerVerified(email, PASSWORD, "UTC");
        String accountId = jdbc.queryForObject("SELECT id::text FROM accounts WHERE email = ?", String.class, email);
        mailbox.failFor(email);
        TestBrowser b = browser();
        b.refreshCsrf();
        assertThat(b.post("/api/v1/auth/password-reset", map("email", email)).status()).isEqualTo(202);

        Map<String, Object> row = awaitAttempt(accountId, 1);
        assertThat(row.get("recipient")).as("only the account ID is queued").isNull();
        assertThat(String.valueOf(row.get("last_error"))).isEqualTo("IllegalStateException");

        mailbox.recover(email);
        clock.advance(Duration.ofMinutes(2));
        emails.sendDue();
        assertThat(mailbox.await(email, "Reset your NextStep password").text()).contains("#token=");
        assertThat(queued(accountId)).isZero();
    }

    @Test
    void deliveryIsAbandonedAfterSixAttempts() {
        String email = uniqueEmail();
        registerVerified(email, PASSWORD, "UTC");
        String accountId = jdbc.queryForObject("SELECT id::text FROM accounts WHERE email = ?", String.class, email);
        mailbox.failFor(email);
        TestBrowser b = browser();
        b.refreshCsrf();
        b.post("/api/v1/auth/password-reset", map("email", email));
        awaitAttempt(accountId, 1);
        for (int i = 0; i < 10 && queued(accountId) > 0; i++) {
            clock.advance(Duration.ofHours(4));
            emails.sendDue();
        }
        assertThat(queued(accountId)).isZero();
        mailbox.assertNothingFor(email, Duration.ofMillis(100));
    }

    private int queued(String accountId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE account_id = CAST(? AS uuid)",
                Integer.class, accountId);
        return count == null ? 0 : count;
    }

    private Map<String, Object> awaitAttempt(String accountId, int attempts) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var rows = jdbc.queryForList("SELECT recipient, attempts, last_error FROM email_outbox WHERE account_id = CAST(? AS uuid)",
                    accountId);
            if (!rows.isEmpty() && ((Number) rows.getFirst().get("attempts")).intValue() >= attempts) {
                return rows.getFirst();
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        throw new AssertionError("The email was not attempted " + attempts + " time(s)");
    }
}
