package com.nextstep.api.identity;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

/** The identity stored in the server-side session. The name is the account ID, used to find all its sessions. */
public record AccountPrincipal(UUID id, AccountKind kind, Instant demoExpiresAt) implements Principal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static AccountPrincipal of(Account account) {
        return new AccountPrincipal(account.getId(), account.getKind(), account.getDemoExpiresAt());
    }

    @Override
    public String getName() {
        return id.toString();
    }

    public boolean isDemo() {
        return kind == AccountKind.DEMO;
    }
}

