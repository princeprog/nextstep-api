package com.nextstep.api.identity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    public SessionSupport(SecurityContextRepository contextRepository, CsrfTokenRepository csrfTokenRepository,
                          JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.contextRepository = contextRepository;
        this.csrfStrategy = new CsrfAuthenticationStrategy(csrfTokenRepository);
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
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

    /** Removes every stored session of several accounts in one statement. */
    public int deleteAllSessions(Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return 0;
        }
        return namedJdbc.update("DELETE FROM spring_session WHERE principal_name IN (:names)",
                new MapSqlParameterSource("names", accountIds.stream().map(UUID::toString).toList()));
    }
}

