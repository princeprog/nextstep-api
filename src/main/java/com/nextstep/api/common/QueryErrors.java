package com.nextstep.api.common;

/** Raises 400 for invalid list query parameters. */
public final class QueryErrors {

    private QueryErrors() {
    }

    public static void throwIfAny(FieldErrors errors) {
        if (!errors.isEmpty()) {
            throw ApiException.badRequest("INVALID_QUERY", "Some list options are not valid.", errors.asMap());
        }
    }
}

