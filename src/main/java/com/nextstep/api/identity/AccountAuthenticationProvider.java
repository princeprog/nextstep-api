package com.nextstep.api.identity;

import java.util.List;
import java.util.Optional;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.nextstep.api.common.Validators;

/**
 * Verifies email and password for normal accounts. Unknown emails take the same hashing path as wrong passwords, and an
 * account whose email isn't confirmed yet gets the same answer as a wrong password. Otherwise registering an address and
 * then signing in with the chosen password would reveal whether the address already had an account.
 */
@Component
public class AccountAuthenticationProvider implements AuthenticationProvider {

    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private volatile String dummyHash;

    public AccountAuthenticationProvider(AccountRepository accounts, PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String email = Validators.normalizeEmail(authentication.getName());
        Object credentials = authentication.getCredentials();
        String password = credentials == null ? "" : credentials.toString();
        Optional<Account> account = accounts.findByEmailAndKind(email, AccountKind.NORMAL);
        if (account.isEmpty()) {
            passwordEncoder.matches(password, dummyHash());
            throw new BadCredentialsException("Invalid credentials");
        }
        boolean matches = passwordEncoder.matches(password, account.get().getPasswordHash());
        if (!matches || account.get().getEmailVerifiedAt() == null) {
            throw new BadCredentialsException("Invalid credentials");
        }
        return UsernamePasswordAuthenticationToken.authenticated(
                AccountPrincipal.of(account.get()), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private String dummyHash() {
        if (dummyHash == null) {
            dummyHash = passwordEncoder.encode("timing-equalizer-placeholder");
        }
        return dummyHash;
    }
}

