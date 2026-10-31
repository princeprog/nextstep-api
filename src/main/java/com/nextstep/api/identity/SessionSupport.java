package com.nextstep.api.identity;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/** Starts and ends server-side sessions. */
@Component
public class SessionSupport {

    private final SecurityContextRepository contextRepository;
    private final CsrfAuthenticationStrategy csrfStrategy;
    private final JdbcTemplate jdbc;
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    public SessionSupport(SecurityContextRepository contextRepository, CsrfTokenRepository csrfTokenRepository, JdbcTemplate jdbc) {
        this.contextRepository = contextRepository;
        this.csrfStrategy = new CsrfAuthenticationStrategy(csrfTokenRepository);
        this.jdbc = jdbc;
    }

    public static AccountPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AccountPrincipal principal) {
            return principal;
        }
        return null;
    }

    /** Authenticates the request: rotates the session ID, stores the context, and replaces the CSRF token. */
    public void signIn(HttpServletRequest request, HttpServletResponse response, AccountPrincipal principal) {
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        request.getSession(true);
        request.changeSessionId();
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(auth);
        holder.setContext(context);
        contextRepository.saveContext(context, request, response);
        csrfStrategy.onAuthentication(auth, request, response);
    }

    /** Ends the current session. */
    public void signOut(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        holder.clearContext();
    }

    /** Removes every stored session belonging to an account. */
    public int deleteAllSessions(UUID accountId) {
        return jdbc.update("DELETE FROM spring_session WHERE principal_name = ?", accountId.toString());
    }

    public int deleteAllSessions(List<UUID> accountIds) {
        int total = 0;
        for (UUID id : accountIds) {
            total += deleteAllSessions(id);
        }
        return total;
    }
}

