package com.nextstep.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.nextstep.api.common.ApiException;

class BoundedPasswordEncoderTest {

    @Test
    void neverRunsMoreHashesAtOnceThanAllowed() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        PasswordEncoder slow = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(30);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    running.decrementAndGet();
                }
                return "hash";
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return encode(rawPassword).equals(encodedPassword);
            }
        };
        BoundedPasswordEncoder bounded = new BoundedPasswordEncoder(slow, 2, Duration.ofSeconds(10));
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 16; i++) {
                results.add(pool.submit(() -> bounded.matches("password", "hash")));
            }
            for (Future<Boolean> result : results) {
                assertThat(result.get()).isTrue();
            }
        }
        assertThat(peak.get()).isEqualTo(2);
    }

    @Test
    void callersThatCannotGetASlotInTimeAreAskedToRetry() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PasswordEncoder blocking = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return "hash";
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return false;
            }
        };
        BoundedPasswordEncoder bounded = new BoundedPasswordEncoder(blocking, 1, Duration.ofMillis(100));
        Thread holder = Thread.ofPlatform().start(() -> bounded.encode("first"));
        started.await();
        assertThatThrownBy(() -> bounded.encode("second"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.code()).isEqualTo("BUSY");
                });
        release.countDown();
        holder.join();
    }
}
