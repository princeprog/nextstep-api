package com.nextstep.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

class RateLimiterWindowTest {

    @Test
    void estimateWeighsThePreviousWindowByItsRemainingOverlap() {
        RateLimiter.Window window = RateLimiter.Window.at(Instant.parse("2030-01-01T00:45:00Z"), Duration.ofHours(1));
        assertThat(window.start()).isEqualTo(OffsetDateTime.parse("2030-01-01T00:00:00Z"));
        assertThat(window.previousStart()).isEqualTo(OffsetDateTime.parse("2029-12-31T23:00:00Z"));
        assertThat(window.expiresAt()).isEqualTo(OffsetDateTime.parse("2030-01-01T02:00:00Z"));
        // 45 minutes in, a quarter of the previous hour still overlaps: 8 * 0.25 + 2.
        assertThat(window.estimate(2, 8)).isEqualTo(4.0);
    }
}
