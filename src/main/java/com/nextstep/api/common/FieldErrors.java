package com.nextstep.api.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** Collects the first validation message for each field. */
public final class FieldErrors {

    private final Map<String, String> errors = new LinkedHashMap<>();

    public void add(String field, String message) {
        errors.putIfAbsent(field, message);
    }

    public boolean has(String field) {
        return errors.containsKey(field);
    }

    public boolean isEmpty() {
        return errors.isEmpty();
    }

    public Map<String, String> asMap() {
        return errors;
    }

    public void throwIfAny() {
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
    }
}

