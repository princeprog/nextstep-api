package com.nextstep.api.identity;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Records deleted accounts by their random ID only, so the deletions can be applied again after a backup is restored
 * (see the restore runbook and the nextstep_reapply_deletions() database function).
 */
@Component
public class DeletionLedger {

    public enum Reason { REQUESTED, UNVERIFIED }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DeletionLedger(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(Collection<UUID> accountIds, Reason reason) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (UUID id : accountIds) {
            jdbc.update("INSERT INTO account_deletions (account_id, deleted_at, reason) VALUES (?, ?, ?) "
                    + "ON CONFLICT (account_id) DO NOTHING", id, now, reason.name());
        }
    }

    /** Forgets deletions older than the cutoff, which must lie beyond the backup retention window. */
    public int purgeBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM account_deletions WHERE deleted_at < ?", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
    }
}
