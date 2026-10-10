package com.hybrid.platform.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private final AtomicLong now = new AtomicLong(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };
    private final RateLimiter limiter = new RateLimiter(clock);
    private final RateLimiter.Rule rule = new RateLimiter.Rule("login", 3, Duration.ofMinutes(1));

    @Test
    void allowsUpToLimitThenBlocksUntilWindowResets() {
        assertThat(limiter.tryAcquire(rule, "1.2.3.4")).isZero();
        assertThat(limiter.tryAcquire(rule, "1.2.3.4")).isZero();
        assertThat(limiter.tryAcquire(rule, "1.2.3.4")).isZero();
        assertThat(limiter.tryAcquire(rule, "1.2.3.4")).isEqualTo(60);

        now.addAndGet(30_000);
        assertThat(limiter.tryAcquire(rule, "1.2.3.4")).isEqualTo(30);

        now.addAndGet(30_000);
        assertThat(limiter.tryAcquire(rule, "1.2.3.4")).isZero();
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 3; i++) limiter.tryAcquire(rule, "a");
        assertThat(limiter.tryAcquire(rule, "a")).isPositive();
        assertThat(limiter.tryAcquire(rule, "b")).isZero();
    }

    @Test
    void evictsOldWindows() {
        limiter.tryAcquire(rule, "a");
        now.addAndGet(Duration.ofHours(2).toMillis());
        limiter.evictExpired(Duration.ofHours(1));
        assertThat(limiter.size()).isZero();
    }
}
