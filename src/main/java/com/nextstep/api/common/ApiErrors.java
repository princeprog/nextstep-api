package com.nextstep.api.common;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/** Writes the standard JSON error from servlet filters, where @RestControllerAdvice does not apply. */
public final class ApiErrors {

    private ApiErrors() {
    }

    public static void write(ObjectMapper objectMapper, HttpServletRequest request, HttpServletResponse response,
                             HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(response.getOutputStream(),
                new ApiError(code, message, null, RequestIdFilter.current(request)));
    }
}
