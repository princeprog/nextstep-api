package com.nextstep.api.identity;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.nextstep.api.common.ApiException;

/**
 * Limits how many password hashes run at once. Argon2id deliberately uses about 19 MiB of memory per hash, so an
 * unbounded burst of sign-in attempts from many addresses could exhaust a small server. Callers wait briefly for a
 * slot and otherwise get a 503 they can retry.
 */
public final class BoundedPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final Semaphore slots;
    private final Duration maxWait;

    public BoundedPasswordEncoder(PasswordEncoder delegate, int maxConcurrent, Duration maxWait) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be at least 1");
        }
        this.delegate = delegate;
        this.slots = new Semaphore(maxConcurrent, true);
        this.maxWait = maxWait;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return withSlot(() -> delegate.encode(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return withSlot(() -> delegate.matches(rawPassword, encodedPassword));
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    private <T> T withSlot(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw busy();
        }
        if (!acquired) {
            throw busy();
        }
        try {
            return work.get();
        } finally {
            slots.release();
        }
    }

    private static ApiException busy() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BUSY",
                "NextStep is busy right now. Try again in a moment.", null);
    }
}
