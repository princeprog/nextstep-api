package com.nextstep.api.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.nextstep.api.common.ApiException;
import com.nextstep.api.common.NextStepProperties;
import com.nextstep.api.common.RateLimiter;
import com.nextstep.api.mail.EmailMessage;
import com.nextstep.api.mail.EmailSender;

import jakarta.annotation.PreDestroy;

/**
 * Account emails go through an outbox table, so a request never waits on the mail provider and a provider outage only
 * delays mail. Emails are sent right after the request's transaction commits, and every 30 seconds for retries.
 * Rows are claimed with SKIP LOCKED, so several API instances never send the same email twice. Links are created
 * when an email is sent; the outbox never holds a usable token. Sending always happens on the outbox's own thread, and
 * a run stops at the first failure, so a mail provider outage costs one timeout per run and never holds up the
 * scheduler's other jobs.
 */
@Component
public class AccountEmails {

    public enum Kind { VERIFY_EMAIL, ACCOUNT_EXISTS, RESET_PASSWORD, PASSWORD_CHANGED, ACCOUNT_DELETED }

    static final int MAX_ATTEMPTS = 6;
    private static final int BATCH = 20;
    private static final int MAX_BATCHES_PER_RUN = 50;
    private static final Logger log = LoggerFactory.getLogger(AccountEmails.class);

    private record Row(UUID id, Kind kind, UUID accountId, String recipient, int attempts) {
    }

    private record BatchResult(int handled, boolean stop) {
    }

    /** An email ready to send, and the link token it carries, if any. */
    private record Composed(EmailMessage message, AccountTokens.Purpose purpose, AccountTokens.Issued link) {

        Composed(EmailMessage message) {
            this(message, null, null);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccountRepository accounts;
    private final AccountTokens tokens;
    private final EmailSender sender;
    private final RateLimiter rateLimiter;
    private final NextStepProperties properties;
    private final Clock clock;
    // One worker; a burst of requests coalesces into a single pending run, which sends everything that is due.
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "email-outbox");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardPolicy());

    public AccountEmails(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, AccountRepository accounts,
                         AccountTokens tokens, EmailSender sender, RateLimiter rateLimiter, NextStepProperties properties,
                         Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.accounts = accounts;
        this.tokens = tokens;
        this.sender = sender;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.clock = clock;
    }

    public void enqueue(Kind kind, UUID accountId) {
        insert(kind, accountId, null);
    }

    /** For the deletion notice, which is sent after the account row is gone. */
    public void enqueueTo(Kind kind, String recipient) {
        insert(kind, null, recipient);
    }

    private void insert(Kind kind, UUID accountId, String recipient) {
        OffsetDateTime now = utc(clock.instant());
        jdbc.update("""
                INSERT INTO email_outbox (id, kind, account_id, recipient, created_at, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), kind.name(), accountId, recipient, now, now);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendSoon();
                }
            });
        } else {
            sendSoon();
        }
    }

    private void sendSoon() {
        worker.execute(() -> {
            try {
                sendDue();
            } catch (RuntimeException ex) {
                log.warn("Sending queued email failed ({})", ex.getClass().getSimpleName());
            }
        });
    }

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "${nextstep.mail.poll-interval:PT30S}")
    void retry() {
        sendSoon();
    }

    /** Sends due emails in batches until none are left or one fails; returns how many rows were handled. */
    public int sendDue() {
        int handled = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            BatchResult result = transactions.execute(status -> sendBatch());
            if (result == null) {
                break;
            }
            handled += result.handled();
            if (result.stop() || result.handled() == 0) {
                break;
            }
        }
        return handled;
    }

    private BatchResult sendBatch() {
        Instant now = clock.instant();
        List<Row> due = jdbc.query("""
                SELECT id, kind, account_id, recipient, attempts FROM email_outbox
                WHERE next_attempt_at <= ? ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED
                """, (rs, n) -> new Row(rs.getObject(1, UUID.class), Kind.valueOf(rs.getString(2)),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getInt(5)), utc(now), BATCH);
        int handled = 0;
        for (Row row : due) {
            try {
                rateLimiter.requireCapacity("email-global", "all", properties.getMail().getGlobalPerHour(), Duration.ofHours(1));
            } catch (ApiException capReached) {
                log.warn("The hourly email limit is reached; queued emails will be sent later");
                return new BatchResult(handled, true);
            }
            Composed composed = null;
            boolean sent = false;
            try {
                composed = compose(row);
                if (composed != null) {
                    sender.send(composed.message());
                    sent = true;
                    rateLimiter.record("email-global", "all", Duration.ofHours(1));
                    if (composed.link() != null) {
                        // Only now does the new link replace the older ones, so a failed send never breaks a link
                        // that is already in the inbox.
                        tokens.retireOthers(row.accountId(), composed.purpose(), composed.link().id());
                    }
                }
                jdbc.update("DELETE FROM email_outbox WHERE id = ?", row.id());
            } catch (RuntimeException ex) {
                if (!sent && composed != null && composed.link() != null) {
                    tokens.discard(composed.link().id());
                }
                // The rest of the batch would most likely fail the same way (the provider is down or refusing), so
                // the next run tries again.
                fail(row, now, ex);
                return new BatchResult(handled + 1, true);
            }
            handled++;
        }
        return new BatchResult(handled, false);
    }

    private void fail(Row row, Instant now, RuntimeException ex) {
        int attempts = row.attempts() + 1;
        String reason = ex.getClass().getSimpleName();
        if (attempts >= MAX_ATTEMPTS) {
            jdbc.update("DELETE FROM email_outbox WHERE id = ?", row.id());
            log.error("Gave up on a {} email after {} attempts ({})", row.kind(), attempts, reason);
            return;
        }
        jdbc.update("UPDATE email_outbox SET attempts = ?, next_attempt_at = ?, last_error = ? WHERE id = ?",
                attempts, utc(now.plus(backoff(attempts))), reason, row.id());
        log.warn("Sending a {} email failed on attempt {} ({}); it will be retried", row.kind(), attempts, reason);
    }

    static Duration backoff(int attempts) {
        return switch (attempts) {
            case 1 -> Duration.ofMinutes(1);
            case 2 -> Duration.ofMinutes(5);
            case 3 -> Duration.ofMinutes(15);
            case 4 -> Duration.ofHours(1);
            default -> Duration.ofHours(3);
        };
    }

    /** Builds the email, or returns null when it is no longer needed (for example the email was verified meanwhile). */
    private Composed compose(Row row) {
        if (row.kind() == Kind.ACCOUNT_DELETED) {
            return new Composed(AccountEmailTemplates.accountDeleted(row.recipient()));
        }
        Account account = accounts.findById(row.accountId()).orElse(null);
        if (account == null || account.getEmail() == null) {
            return null;
        }
        String origin = properties.getAppPublicOrigin();
        NextStepProperties.Accounts settings = properties.getAccounts();
        return switch (row.kind()) {
            case VERIFY_EMAIL -> {
                if (account.getEmailVerifiedAt() != null) {
                    yield null;
                }
                AccountTokens.Issued link = tokens.issue(account.getId(), AccountTokens.Purpose.VERIFY_EMAIL,
                        settings.getVerificationTtl());
                yield new Composed(AccountEmailTemplates.verifyEmail(account.getEmail(),
                        origin + "/verify-email#token=" + link.token(), settings.getVerificationTtl()),
                        AccountTokens.Purpose.VERIFY_EMAIL, link);
            }
            case ACCOUNT_EXISTS -> new Composed(AccountEmailTemplates.accountExists(account.getEmail(), origin + "/login",
                    origin + "/forgot-password"));
            case RESET_PASSWORD -> {
                AccountTokens.Issued link = tokens.issue(account.getId(), AccountTokens.Purpose.RESET_PASSWORD,
                        settings.getResetTtl());
                yield new Composed(AccountEmailTemplates.resetPassword(account.getEmail(),
                        origin + "/reset-password#token=" + link.token(), settings.getResetTtl()),
                        AccountTokens.Purpose.RESET_PASSWORD, link);
            }
            case PASSWORD_CHANGED -> new Composed(AccountEmailTemplates.passwordChanged(account.getEmail(),
                    origin + "/forgot-password"));
            case ACCOUNT_DELETED -> throw new IllegalStateException("handled above");
        };
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
