package com.nextstep.api.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import com.nextstep.api.common.JsonInput;
import com.nextstep.api.common.NextStepProperties;

/** Rules for new passwords: 12 to 128 characters and not found in a known data breach. */
@Component
public class PasswordPolicy {

    static final int MIN_LENGTH = 12;
    static final int MAX_LENGTH = 128;

    private final BreachedPasswordChecker breached;

    public PasswordPolicy(BreachedPasswordChecker breached) {
        this.breached = breached;
    }

    /** Returns the new password, or null after recording a field error. */
    public String check(JsonInput in, String field, String blankMessage) {
        String password = in.string(field);
        if (in.errors().has(field)) {
            return null;
        }
        if (password == null || password.isEmpty()) {
            in.errors().add(field, blankMessage);
            return null;
        }
        if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            in.errors().add(field, "Use between 12 and 128 characters.");
            return null;
        }
        if (breached.isBreached(password)) {
            in.errors().add(field, "This password has appeared in a data breach. Choose a different one.");
            return null;
        }
        return password;
    }

    @Configuration(proxyBeanMethods = false)
    static class CheckerConfig {

        @Bean
        BreachedPasswordChecker breachedPasswordChecker(NextStepProperties properties) {
            NextStepProperties.Passwords settings = properties.getPasswords();
            if (!settings.isBreachCheck()) {
                return password -> false;
            }
            return new PwnedPasswordsChecker(settings.getBreachCheckUrl(), settings.getBreachCheckTimeout());
        }
    }
}
