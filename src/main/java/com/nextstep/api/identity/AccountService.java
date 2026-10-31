package com.nextstep.api.identity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nextstep.api.common.ApiException;
import com.nextstep.api.common.JsonInput;
import com.nextstep.api.common.Validators;
import com.nextstep.api.identity.AccountEmails.Kind;
import com.nextstep.api.identity.AccountTokens.Purpose;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    /** Registration fields after validation. */
    public record Registration(String displayName, String email, String password, ZoneId zone) {
    }

    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final SessionSupport sessions;
    private final AccountTokens tokens;
    private final AccountEmails emails;
    private final DeletionLedger ledger;
    private final Clock clock;

    public AccountService(AccountRepository accounts, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
                          SessionSupport sessions, AccountTokens tokens, AccountEmails emails, DeletionLedger ledger,
                          Clock clock) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.sessions = sessions;
        this.tokens = tokens;
        this.emails = emails;
        this.ledger = ledger;
        this.clock = clock;
    }

    /** Checks every registration field, including the breached-password check, without looking at any account. */
    public Registration validateRegistration(JsonInput in) {
        String displayName = Validators.requiredText(in, "displayName", 80, "Enter your name.");
        String email = requiredEmail(in);
        String password = passwordPolicy.check(in, "password", "Enter a password.");
        ZoneId zone = Validators.zone(in, "timeZone", true);
        in.errors().throwIfAny();
        return new Registration(displayName, email, password, zone);
    }

    /**
     * Creates an unverified account and queues its confirmation link. When the email already has an account, that
     * account doesn't change: its owner gets an "account already exists" email, or a fresh link while still unverified.
     * Both paths hash the password and answer the same way, so registration doesn't reveal which emails have accounts.
     */
    @Transactional
    public void register(Registration registration) {
        String passwordHash = passwordEncoder.encode(registration.password());
        Optional<Account> existing = accounts.findByEmailAndKind(registration.email(), AccountKind.NORMAL);
        if (existing.isPresent()) {
            Account account = existing.get();
            emails.enqueue(account.getEmailVerifiedAt() == null ? Kind.VERIFY_EMAIL : Kind.ACCOUNT_EXISTS, account.getId());
            return;
        }
        Instant now = clock.instant();
        Account account = new Account();
        account.setKind(AccountKind.NORMAL);
        account.setDisplayName(registration.displayName());
        account.setEmail(registration.email());
        account.setPasswordHash(passwordHash);
        account.setTimeZone(registration.zone().getId());
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        accounts.saveAndFlush(account);
        emails.enqueue(Kind.VERIFY_EMAIL, account.getId());
    }

    @Transactional
    public void verifyEmail(String token) {
        UUID accountId = tokens.consume(token, Purpose.VERIFY_EMAIL).orElseThrow(AccountService::invalidLink);
        Account account = accounts.findById(accountId).orElseThrow(AccountService::invalidLink);
        if (account.getEmailVerifiedAt() == null) {
            Instant now = clock.instant();
            account.setEmailVerifiedAt(now);
            account.setUpdatedAt(now);
        }
    }

    /** Queues a new link for an unverified account. Unknown or already verified emails get nothing, silently. */
    @Transactional
    public void resendVerification(String email) {
        accounts.findByEmailAndKind(email, AccountKind.NORMAL)
                .filter(account -> account.getEmailVerifiedAt() == null)
                .ifPresent(account -> emails.enqueue(Kind.VERIFY_EMAIL, account.getId()));
    }

    /** Queues a reset link when the email has an account. Unknown emails get the same answer and no email. */
    @Transactional
    public void requestPasswordReset(String email) {
        accounts.findByEmailAndKind(email, AccountKind.NORMAL)
                .ifPresent(account -> emails.enqueue(Kind.RESET_PASSWORD, account.getId()));
    }

    /**
     * Sets a new password from a reset link and signs the account out everywhere. Completing a reset proves control of
     * the inbox, so it also verifies the email.
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        UUID accountId = tokens.consume(token, Purpose.RESET_PASSWORD).orElseThrow(AccountService::invalidLink);
        Account account = accounts.findById(accountId).orElseThrow(AccountService::invalidLink);
        Instant now = clock.instant();
        account.setPasswordHash(passwordEncoder.encode(newPassword));
        if (account.getEmailVerifiedAt() == null) {
            account.setEmailVerifiedAt(now);
        }
        account.setUpdatedAt(now);
        tokens.revokeUnused(accountId, Purpose.RESET_PASSWORD);
        sessions.deleteAllSessions(accountId);
        emails.enqueue(Kind.PASSWORD_CHANGED, accountId);
    }

    /** Changes the password and signs out every other session. Returns false, changing nothing, if the current password is wrong. */
    @Transactional
    public boolean changePassword(UUID accountId, String currentPassword, String newPassword, String keepSessionId) {
        Account account = normalAccount(accountId);
        if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            return false;
        }
        account.setPasswordHash(passwordEncoder.encode(newPassword));
        account.setUpdatedAt(clock.instant());
        tokens.revokeUnused(accountId, Purpose.RESET_PASSWORD);
        sessions.deleteOtherSessions(accountId, keepSessionId);
        emails.enqueue(Kind.PASSWORD_CHANGED, accountId);
        return true;
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Sign in to continue.", null));
    }

    @Transactional
    public Account updateProfile(UUID id, JsonInput in) {
        Account account = get(id);
        String displayName = in.present("displayName")
                ? Validators.requiredText(in, "displayName", 80, "Enter your name.") : account.getDisplayName();
        ZoneId zone = in.present("timeZone") ? Validators.zone(in, "timeZone", true) : ZoneId.of(account.getTimeZone());
        in.errors().throwIfAny();
        account.setDisplayName(displayName);
        account.setTimeZone(zone.getId());
        account.setUpdatedAt(clock.instant());
        return account;
    }

    /**
     * Permanently deletes a normal account, its records (by cascade), and every session, after re-checking the password.
     * The deletion is recorded so it can be applied again if an older backup is restored. Returns false, changing
     * nothing, if the password is wrong.
     */
    @Transactional
    public boolean delete(UUID id, String password) {
        Account account = normalAccount(id);
        if (!passwordEncoder.matches(password, account.getPasswordHash())) {
            return false;
        }
        ledger.record(List.of(id), DeletionLedger.Reason.REQUESTED);
        emails.enqueueTo(Kind.ACCOUNT_DELETED, account.getEmail());
        sessions.deleteAllSessions(id);
        accounts.delete(account);
        log.info("Deleted account {} at its owner's request", id);
        return true;
    }

    private Account normalAccount(UUID id) {
        Account account = get(id);
        if (account.getKind() != AccountKind.NORMAL) {
            throw ApiException.conflict("DEMO_ACCOUNT",
                    "Demo workspaces don't have a password or a permanent account. Leave the demo instead; it is removed when it expires.");
        }
        return account;
    }

    static String requiredEmail(JsonInput in) {
        String email = Validators.normalizeEmail(in.string("email"));
        if (!in.errors().has("email")) {
            if (email == null || email.isEmpty()) {
                in.errors().add("email", "Enter your email address.");
            } else if (!Validators.isEmail(email)) {
                in.errors().add("email", "Enter a valid email address.");
            }
        }
        return email;
    }

    static ApiException invalidLink() {
        return ApiException.badRequest("TOKEN_INVALID",
                "This link is invalid, was already used, or has expired. Request a new one.", null);
    }
}
