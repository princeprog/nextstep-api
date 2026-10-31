package com.nextstep.api.common;

import java.util.Map;

import org.springframework.http.HttpStatus;

/** A failure with a stable error code and a message that is safe to show to the user. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, String> fieldErrors;

    public ApiException(HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = fieldErrors == null || fieldErrors.isEmpty() ? null : Map.copyOf(fieldErrors);
    }

    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "We couldn't find that record.", null);
    }

    public static ApiException validation(Map<String, String> fieldErrors) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_ERROR", "Check the highlighted fields.", fieldErrors);
    }

    public static ApiException badRequest(String code, String message, Map<String, String> fieldErrors) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, fieldErrors);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message, null);
    }

    public static ApiException archived() {
        return conflict("APPLICATION_ARCHIVED", "This application is archived. Restore it before making changes.");
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Map<String, String> fieldErrors() { return fieldErrors; }
}

