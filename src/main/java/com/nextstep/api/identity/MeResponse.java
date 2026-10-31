package com.nextstep.api.identity;

/** emailVerified is null for demo workspaces, which have no email. */
public record MeResponse(String id, String kind, String displayName, String email, String timeZone, String demoExpiresAt,
                         Boolean emailVerified) {

    public static MeResponse of(Account account) {
        return new MeResponse(
                account.getId().toString(),
                account.getKind().name(),
                account.getDisplayName(),
                account.getEmail(),
                account.getTimeZone(),
                account.getDemoExpiresAt() == null ? null : account.getDemoExpiresAt().toString(),
                account.getKind() == AccountKind.DEMO ? null : account.getEmailVerifiedAt() != null);
    }
}

