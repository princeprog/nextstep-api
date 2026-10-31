package com.nextstep.api.identity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nextstep.api.common.ApiException;
import com.nextstep.api.common.JsonInput;
import com.nextstep.api.common.Validators;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final SessionSupport sessions;
    private final Clock clock;

    public AccountService(AccountRepository accounts, PasswordEncoder passwordEncoder, SessionSupport sessions, Clock clock) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional
    public Account register(JsonInput in) {
        String displayName = Validators.requiredText(in, "displayName", 80, "Enter your name.");
        String rawEmail = in.string("email");
        String email = Validators.normalizeEmail(rawEmail);
        if (!in.errors().has("email")) {
            if (email == null || email.isEmpty()) {
                in.errors().add("email", "Enter your email address.");
            } else if (!Validators.isEmail(email)) {
                in.errors().add("email", "Enter a valid email address.");
            }
        }
        String password = in.string("password");
        if (!in.errors().has("password")) {
            if (password == null || password.isEmpty()) {
                in.errors().add("password", "Enter a password.");
            } else if (password.length() < 12 || password.length() > 128) {
                in.errors().add("password", "Use between 12 and 128 characters.");
            }
        }
        ZoneId zone = Validators.zone(in, "timeZone", true);
        in.errors().throwIfAny();
        if (accounts.existsByEmailAndKind(email, AccountKind.NORMAL)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "An account already uses this email.",
                    Map.of("email", "An account already uses this email. Sign in instead."));
        }
        Instant now = clock.instant();
        Account account = new Account();
        account.setKind(AccountKind.NORMAL);
        account.setDisplayName(displayName);
        account.setEmail(email);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setTimeZone(zone.getId());
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        return accounts.saveAndFlush(account);
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

    /** Permanently deletes a normal account, its owned records (by cascade), and every session. */
    @Transactional
    public void delete(UUID id) {
        Account account = get(id);
        if (account.getKind() != AccountKind.NORMAL) {
            throw ApiException.conflict("DEMO_ACCOUNT",
                    "Demo workspaces can't be deleted this way. Leave the demo instead; it is removed when it expires.");
        }
        sessions.deleteAllSessions(id);
        accounts.delete(account);
    }
}

