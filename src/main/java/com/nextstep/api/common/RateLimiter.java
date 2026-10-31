package com.nextstep.api.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Rate limits shared by every API instance through PostgreSQL.
 *
 * Each bucket counts hits in fixed windows and estimates a sliding window by weighting the previous window by the part
 * of it that still overlaps the last window length. Client addresses and emails are stored only as keyed digests
 * ({@link IdentifierHasher}), and rows are removed two windows after they start ({@link MaintenanceJob}).
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final String HIT = """
            WITH hit AS (
                INSERT INTO rate_limit_counters (bucket, key_hash, window_start, hits, expires_at)
                VALUES (?, ?, ?, 1, ?)
                ON CONFLICT (bucket, key_hash, window_start) DO UPDATE SET hits = rate_limit_counters.hits + 1
                RETURNING hits
            )
            SELECT (SELECT hits FROM hit),
                   COALESCE((SELECT hits FROM rate_limit_counters
                             WHERE bucket = ? AND key_hash = ? AND window_start = ?), 0)
            """;

    private static final String PEEK = """
            SELECT COALESCE(SUM(hits) FILTER (WHERE window_start = ?), 0),
                   COALESCE(SUM(hits) FILTER (WHERE window_start = ?), 0)
            FROM rate_limit_counters
            WHERE bucket = ? AND key_hash = ? AND window_start IN (?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final IdentifierHasher hasher;

    public RateLimiter(JdbcTemplate jdbc, Clock clock, IdentifierHasher hasher) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.hasher = hasher;
    }

    /**
     * Counts this request and rejects it when the bucket is over its limit. Counting comes first, so simultaneous
     * requests can't all pass before any of them is recorded.
     */
    public void check(String bucket, String key, int max, Duration window) {
        if (hit(bucket, key, window) > max) {
            throw limited();
        }
    }

    /** Rejects the request when one more hit would pass the limit, without counting it. */
    public void requireCapacity(String bucket, String key, int max, Duration window) {
        Window w = Window.at(clock.instant(), window);
        String keyHash = hasher.hash(bucket, key);
        Double estimate = jdbc.queryForObject(PEEK, (rs, row) -> w.estimate(rs.getLong(1), rs.getLong(2)),
                w.start(), w.previousStart(), bucket, keyHash, w.start(), w.previousStart());
        if (estimate != null && estimate + 1 > max) {
            throw limited();
        }
    }

    /** Counts a hit without checking the limit, for example a failed sign-in. */
    public void record(String bucket, String key, Duration window) {
        hit(bucket, key, window);
    }

    /** Takes back one hit counted by {@link #check}, for example after a successful sign-in. */
    public void refund(String bucket, String key, Duration window) {
        Window w = Window.at(clock.instant(), window);
        jdbc.update("UPDATE rate_limit_counters SET hits = hits - 1 WHERE bucket = ? AND key_hash = ? AND window_start = ? AND hits > 0",
                bucket, hasher.hash(bucket, key), w.start());
    }

    /** Forgets every hit for a key, for example an email's failed sign-ins after its owner resets the password. */
    public void clear(String bucket, String key) {
        jdbc.update("DELETE FROM rate_limit_counters WHERE bucket = ? AND key_hash = ?", bucket, hasher.hash(bucket, key));
    }

    public int deleteExpired() {
        int removed = jdbc.update("DELETE FROM rate_limit_counters WHERE expires_at < ?", utc(clock.instant()));
        if (removed > 0) {
            log.info("Removed {} expired rate-limit counter(s)", removed);
        }
        return removed;
    }

    private double hit(String bucket, String key, Duration window) {
        Window w = Window.at(clock.instant(), window);
        String keyHash = hasher.hash(bucket, key);
        Double estimate = jdbc.queryForObject(HIT, (rs, row) -> w.estimate(rs.getLong(1), rs.getLong(2)),
                bucket, keyHash, w.start(), w.expiresAt(), bucket, keyHash, w.previousStart());
        return estimate == null ? 0 : estimate;
    }

    private static ApiException limited() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Too many attempts. Wait a few minutes and try again.", null);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** The fixed window containing an instant, the one before it, and how far into the current window the instant is. */
    record Window(OffsetDateTime start, OffsetDateTime previousStart, OffsetDateTime expiresAt, double elapsed) {

        static Window at(Instant now, Duration length) {
            long size = length.toMillis();
            long nowMillis = now.toEpochMilli();
            long startMillis = Math.floorDiv(nowMillis, size) * size;
            return new Window(utc(Instant.ofEpochMilli(startMillis)), utc(Instant.ofEpochMilli(startMillis - size)),
                    utc(Instant.ofEpochMilli(startMillis + 2 * size)), (double) (nowMillis - startMillis) / size);
        }

        /** Hits in the last window length: all current hits plus the still-overlapping share of the previous window. */
        double estimate(long current, long previous) {
            return previous * (1.0 - elapsed) + current;
        }
    }
}
