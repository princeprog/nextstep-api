package com.nextstep.api.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Single-use, expiring tokens for email links. A token is 32 random bytes; only its SHA-256 hash is stored, so a copy of
 * the database can't be used to verify an email or reset a password. Issuing a token replaces any unused token of the
 * same purpose, so only the newest link works.
 */
@Component
public class AccountTokens {

    public enum Purpose { VERIFY_EMAIL, RESET_PASSWORD }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AccountTokens(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Creates a token and returns its raw value for the link. Any older unused token of this purpose stops working. */
    public String issue(UUID accountId, Purpose purpose, Duration ttl) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        revokeUnused(accountId, purpose);
        jdbc.update("""
                INSERT INTO account_tokens (id, account_id, purpose, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), accountId, purpose.name(), hash(token), utc(now), utc(now.plus(ttl)));
        return token;
    }

    /** Marks a valid token used and returns its account. Unknown, used, expired, or wrong-purpose tokens return empty. */
    public Optional<UUID> consume(String token, Purpose purpose) {
        if (token == null || !FORMAT.matcher(token).matches()) {
            return Optional.empty();
        }
        OffsetDateTime now = utc(clock.instant());
        List<UUID> accounts = jdbc.query("""
                UPDATE account_tokens SET used_at = ?
                WHERE token_hash = ? AND purpose = ? AND used_at IS NULL AND expires_at > ?
                RETURNING account_id
                """, (rs, row) -> rs.getObject(1, UUID.class), now, hash(token), purpose.name(), now);
        return accounts.stream().findFirst();
    }

    public void revokeUnused(UUID accountId, Purpose purpose) {
        jdbc.update("DELETE FROM account_tokens WHERE account_id = ? AND purpose = ? AND used_at IS NULL",
                accountId, purpose.name());
    }

    /** Removes tokens that expired or were used before the cutoff. */
    public int deleteStale(Instant cutoff) {
        OffsetDateTime before = utc(cutoff);
        return jdbc.update("DELETE FROM account_tokens WHERE expires_at < ? OR used_at < ?", before, before);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
