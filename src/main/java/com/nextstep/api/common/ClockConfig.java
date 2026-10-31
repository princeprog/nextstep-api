package com.nextstep.api.common;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /**
     * All business time decisions use this clock so tests can control boundaries precisely.
     * It ticks in microseconds to match PostgreSQL timestamp precision, so stored and returned instants agree.
     */
    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}

