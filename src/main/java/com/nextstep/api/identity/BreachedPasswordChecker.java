package com.nextstep.api.identity;

/** Tells whether a password appears in a known data breach. Implementations must never send the password itself. */
@FunctionalInterface
public interface BreachedPasswordChecker {

    boolean isBreached(String password);
}
