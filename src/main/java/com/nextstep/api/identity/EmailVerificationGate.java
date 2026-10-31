package com.nextstep.api.identity;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.nextstep.api.common.ApiErrors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Until its email is verified, an account can only see its profile, export or delete itself, and use the auth
 * endpoints (sign out, resend the link). Everything else answers 403 EMAIL_NOT_VERIFIED. The session caches the
 * verified state; the database is checked before refusing, so verifying in another browser takes effect at once.
 */
@Component
class EmailVerificationGate implements HandlerInterceptor {

    private final AccountRepository accounts;
    private final SessionSupport sessions;
    private final ObjectMapper objectMapper;

    EmailVerificationGate(AccountRepository accounts, SessionSupport sessions, ObjectMapper objectMapper) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        AccountPrincipal principal = SessionSupport.currentPrincipal();
        if (principal == null || principal.emailVerified()
                || allowedWhileUnverified(request.getMethod(), request.getRequestURI())) {
            return true;
        }
        boolean verified = accounts.findById(principal.id()).map(Account::hasVerifiedAccess).orElse(false);
        if (verified) {
            sessions.refreshPrincipal(request, response, principal.withEmailVerified());
            return true;
        }
        ApiErrors.write(objectMapper, request, response, HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED",
                "Confirm your email address to use your workspace. Check your inbox for the link.");
        return false;
    }

    static boolean allowedWhileUnverified(String method, String path) {
        if (path.startsWith("/api/v1/auth/")) {
            return true;
        }
        return switch (path) {
            case "/api/v1/me" -> "GET".equals(method) || "DELETE".equals(method);
            case "/api/v1/me/export" -> "GET".equals(method);
            case "/api/v1/health", "/api/v1/openapi.yaml" -> true;
            default -> false;
        };
    }
}
