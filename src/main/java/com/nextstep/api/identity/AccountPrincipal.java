package com.nextstep.api.identity;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

/**
 * The identity stored in the server-side session. The name is the account ID, used to find all its sessions.
 * emailVerified is a cached hint; the database is checked again before access is refused.
 */
public record AccountPrincipal(UUID id, AccountKind kind, Instant demoExpiresAt, boolean emailVerified)
        implements Principal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static AccountPrincipal of(Account account) {
        return new AccountPrincipal(account.getId(), account.getKind(), account.getDemoExpiresAt(), account.hasVerifiedAccess());
    }

    public AccountPrincipal withEmailVerified() {
        return new AccountPrincipal(id, kind, demoExpiresAt, true);
    }

    @Override
    public String getName() {
        return id.toString();
    }

    public boolean isDemo() {
        return kind == AccountKind.DEMO;
    }
}

