package com.nextstep.api.common;

import java.math.BigDecimal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Reads a JSON object request body while keeping the difference between an omitted field and an explicit null.
 * Type mismatches are recorded as field errors.
 */
public final class JsonInput {

    private final JsonNode node;
    private final FieldErrors errors = new FieldErrors();

    private JsonInput(JsonNode node) {
        this.node = node;
    }

    public static JsonInput of(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return new JsonInput(JsonNodeFactory.instance.objectNode());
        }
        if (!node.isObject()) {
            throw ApiException.badRequest("MALFORMED_REQUEST", "Send the request as a JSON object.", null);
        }
        return new JsonInput(node);
    }

    public FieldErrors errors() {
        return errors;
    }

    public boolean present(String field) {
        return node.has(field);
    }

    public boolean isNull(String field) {
        return present(field) && node.get(field).isNull();
    }

    public String string(String field) {
        if (!present(field) || isNull(field)) {
            return null;
        }
        JsonNode value = node.get(field);
        if (!value.isString()) {
            errors.add(field, "Enter text for this field.");
            return null;
        }
        return value.stringValue();
    }

    public Integer integer(String field) {
        if (!present(field) || isNull(field)) {
            return null;
        }
        JsonNode value = node.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            errors.add(field, "Enter a whole number.");
            return null;
        }
        return value.intValue();
    }

    public BigDecimal decimal(String field) {
        if (!present(field) || isNull(field)) {
            return null;
        }
        JsonNode value = node.get(field);
        try {
            if (value.isString()) {
                String text = value.stringValue().strip();
                if (text.isEmpty()) {
                    return null;
                }
                return new BigDecimal(text);
            }
            if (value.isNumber()) {
                return value.decimalValue();
            }
        } catch (NumberFormatException ex) {
            // reported below
        }
        errors.add(field, "Enter a valid amount.");
        return null;
    }
}

