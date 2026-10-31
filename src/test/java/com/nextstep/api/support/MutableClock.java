package com.nextstep.api.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

/** A clock tests can pin to an exact instant; when unset it follows the system clock. */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> fixed = new AtomicReference<>();

    public void set(Instant instant) { fixed.set(instant); }

    public void advance(Duration duration) { fixed.set(instant().plus(duration)); }

    public void reset() { fixed.set(null); }

    @Override
    public ZoneId getZone() { return ZoneOffset.UTC; }

    @Override
    public Clock withZone(ZoneId zone) { return this; }

    @Override
    public Instant instant() {
        Instant value = fixed.get();
        return value != null ? value : Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}

