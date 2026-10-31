package com.nextstep.api.identity;

import java.io.IOException;
import java.time.Clock;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Enforces the hard demo expiry on every request, independently of when background cleanup runs.
 */
class DemoSessionFilter extends OncePerRequestFilter {

    private final Clock clock;
    private final ObjectMapper objectMapper;

    DemoSessionFilter(Clock clock, ObjectMapper objectMapper) {
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AccountPrincipal principal && principal.isDemo()
                && !clock.instant().isBefore(principal.demoExpiresAt())) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            boolean csrfBootstrap = "GET".equals(request.getMethod())
                    && "/api/v1/auth/csrf".equals(request.getRequestURI());
            if (!csrfBootstrap) {
                SecurityConfig.writeError(objectMapper, request, response, HttpStatus.UNAUTHORIZED, "DEMO_EXPIRED",
                        "This demo has expired. Start a new demo to keep exploring.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

