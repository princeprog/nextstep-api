package com.nextstep.api.common;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Caps request bodies well above anything the API needs (the largest field is a 10,000-character note), so one
 * request can't make the server buffer megabytes of JSON. Declared lengths are rejected up front; chunked bodies
 * fail as soon as they cross the limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    static final String CODE = "PAYLOAD_TOO_LARGE";
    static final String MESSAGE = "This request is too large.";

    private final NextStepProperties properties;
    private final ObjectMapper objectMapper;

    public RequestSizeLimitFilter(NextStepProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long max = properties.getMaxRequestBytes();
        long declared = request.getContentLengthLong();
        if (declared > max) {
            ApiErrors.write(objectMapper, request, response, HttpStatus.CONTENT_TOO_LARGE, CODE, MESSAGE);
            return;
        }
        chain.doFilter(declared >= 0 ? request : new LimitedRequest(request, max), response);
    }

    /** Raised while reading a body without a declared length once it passes the limit. */
    static final class PayloadTooLargeException extends IOException {
        PayloadTooLargeException() {
            super(MESSAGE);
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long max;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedStream(super.getInputStream(), max);
            }
            return stream;
        }
    }

    private static final class LimitedStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long max;
        private long count;

        LimitedStream(ServletInputStream delegate, long max) {
            this.delegate = delegate;
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                add(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                add(read);
            }
            return read;
        }

        private void add(int bytes) throws PayloadTooLargeException {
            count += bytes;
            if (count > max) {
                throw new PayloadTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
