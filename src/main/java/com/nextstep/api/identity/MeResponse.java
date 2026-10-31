package com.nextstep.api.identity;

public record MeResponse(String id, String kind, String displayName, String email, String timeZone, String demoExpiresAt) {

    public static MeResponse of(Account account) {
        return new MeResponse(
                account.getId().toString(),
                account.getKind().name(),
                account.getDisplayName(),
                account.getEmail(),
                account.getTimeZone(),
                account.getDemoExpiresAt() == null ? null : account.getDemoExpiresAt().toString());
    }
}

