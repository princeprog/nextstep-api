package com.nextstep.api.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.nextstep.api.common.ClusterJobLock;
import com.nextstep.api.common.NextStepProperties;

/**
 * Removes accounts never verified within a week (in batches of 500, recorded in the deletion ledger), used or expired
 * link tokens after a day, and deletion records older than the ledger retention. One API instance at a time.
 */
@Component
public class AccountCleanup {

    static final int BATCH = 500;
    private static final Logger log = LoggerFactory.getLogger(AccountCleanup.class);

    // Selects and deletes in one statement, and only rows still unverified, so an account verified meanwhile stays.
    private static final String DELETE_UNVERIFIED = """
            DELETE FROM accounts WHERE id IN (
                SELECT id FROM accounts
                WHERE kind = 'NORMAL' AND email_verified_at IS NULL AND created_at <= ?
                ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED)
            RETURNING id
            """;

    private final JdbcTemplate jdbc;
    private final SessionSupport sessions;
    private final AccountTokens tokens;
    private final DeletionLedger ledger;
    private final ClusterJobLock lock;
    private final NextStepProperties properties;
    private final Clock clock;

    public AccountCleanup(JdbcTemplate jdbc, SessionSupport sessions, AccountTokens tokens, DeletionLedger ledger,
                          ClusterJobLock lock, NextStepProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.tokens = tokens;
        this.ledger = ledger;
        this.lock = lock;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "${nextstep.maintenance-interval:PT15M}")
    public void run() {
        lock.runExclusively("account-cleanup", () -> {
            removeUnverified();
            purgeRecords();
        });
    }

    @Transactional
    public int removeUnverified() {
        Instant cutoff = clock.instant().minus(properties.getAccounts().getUnverifiedTtl());
        List<UUID> removed = jdbc.queryForList(DELETE_UNVERIFIED, UUID.class, OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC), BATCH);
        if (removed.isEmpty()) {
            return 0;
        }
        ledger.record(removed, DeletionLedger.Reason.UNVERIFIED);
        sessions.deleteAllSessions(removed);
        log.info("Removed {} account(s) that were never verified", removed.size());
        return removed.size();
    }

    @Transactional
    public void purgeRecords() {
        Instant now = clock.instant();
        tokens.deleteStale(now.minus(Duration.ofDays(1)));
        ledger.purgeBefore(now.minus(properties.getAccounts().getDeletionLedgerRetention()));
    }
}
