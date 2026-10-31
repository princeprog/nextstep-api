package com.nextstep.api.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
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

    private final AccountRepository accounts;
    private final SessionSupport sessions;
    private final AccountTokens tokens;
    private final DeletionLedger ledger;
    private final ClusterJobLock lock;
    private final NextStepProperties properties;
    private final Clock clock;

    public AccountCleanup(AccountRepository accounts, SessionSupport sessions, AccountTokens tokens, DeletionLedger ledger,
                          ClusterJobLock lock, NextStepProperties properties, Clock clock) {
        this.accounts = accounts;
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
        List<UUID> stale = accounts.findUnverifiedCreatedBefore(cutoff, PageRequest.of(0, BATCH));
        if (stale.isEmpty()) {
            return 0;
        }
        ledger.record(stale, DeletionLedger.Reason.UNVERIFIED);
        sessions.deleteAllSessions(stale);
        accounts.deleteAllByIdInBatch(stale);
        log.info("Removed {} account(s) that were never verified", stale.size());
        return stale.size();
    }

    @Transactional
    public void purgeRecords() {
        Instant now = clock.instant();
        tokens.deleteStale(now.minus(Duration.ofDays(1)));
        ledger.purgeBefore(now.minus(properties.getAccounts().getDeletionLedgerRetention()));
    }
}
