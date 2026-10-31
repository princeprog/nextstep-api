package com.nextstep.api.identity;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;
import com.nextstep.api.support.TestBrowser.Response;

class AccountDeletionTest extends IntegrationTestSupport {

    @Test
    void deletionNeedsThePasswordAndRemovesEverything() {
        String email = uniqueEmail();
        registerVerified(email, PASSWORD, "UTC");
        TestBrowser first = signIn(email, PASSWORD);
        String accountId = first.get("/api/v1/me").text("id");
        String applicationId = createApplication(first);
        createTask(first, applicationId, "2030-01-01", null, "UTC");
        TestBrowser second = signIn(email, PASSWORD);

        Response noPassword = first.delete("/api/v1/me");
        assertThat(noPassword.status()).isEqualTo(422);
        Response wrong = first.delete("/api/v1/me", map("password", "not-the-password"));
        assertThat(wrong.status()).isEqualTo(422);
        assertThat(wrong.fieldError("password")).isNotBlank();
        assertThat(count("applications", "owner_id", accountId)).as("nothing changes on a wrong password").isOne();

        assertThat(first.delete("/api/v1/me", map("password", PASSWORD)).status()).isEqualTo(204);
        assertThat(first.get("/api/v1/me").status()).isEqualTo(401);
        assertThat(second.get("/api/v1/me").status()).as("other sessions are invalidated").isEqualTo(401);
        assertThat(count("applications", "owner_id", accountId)).isZero();
        assertThat(count("tasks", "owner_id", accountId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?", Integer.class, accountId)).isZero();
        assertThat(jdbc.queryForObject("SELECT reason FROM account_deletions WHERE account_id = CAST(? AS uuid)", String.class, accountId))
                .isEqualTo("REQUESTED");
        assertThat(mailbox.await(email, "account was deleted").text()).contains("backup");

        TestBrowser later = browser();
        later.refreshCsrf();
        assertThat(later.post("/api/v1/auth/login", map("email", email, "password", PASSWORD)).status()).isEqualTo(401);
    }

    @Test
    void deletionsAreReappliedAfterRestoringAnOlderBackup() {
        String email = uniqueEmail();
        registerVerified(email, PASSWORD, "UTC");
        TestBrowser user = signIn(email, PASSWORD);
        String accountId = user.get("/api/v1/me").text("id");
        assertThat(user.delete("/api/v1/me", map("password", PASSWORD)).status()).isEqualTo(204);

        // A backup taken before the deletion is restored: the account and its records come back.
        jdbc.update("""
                INSERT INTO accounts (id, kind, display_name, email, password_hash, time_zone, email_verified_at, created_at, updated_at)
                VALUES (CAST(? AS uuid), 'NORMAL', 'Restored', ?, 'restored-hash', 'UTC', now(), now(), now())
                """, accountId, email);
        jdbc.update("""
                INSERT INTO applications (id, owner_id, company, job_title, status, created_at, updated_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), 'Acme', 'Engineer', 'SAVED', now(), now())
                """, accountId);

        Integer removed = jdbc.queryForObject("SELECT nextstep_reapply_deletions()", Integer.class);
        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(count("accounts", "id", accountId)).isZero();
        assertThat(count("applications", "owner_id", accountId)).isZero();
    }

    private int count(String table, String column, String id) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = CAST(? AS uuid)",
                Integer.class, id);
        return count == null ? 0 : count;
    }
}
