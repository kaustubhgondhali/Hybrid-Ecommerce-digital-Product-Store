package com.hybrid.platform.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window counters kept in memory. Limits are per application instance, which is enough to stop
 * credential stuffing and email bombing from a single address.
 */
public class RateLimiter {

    public record Rule(String name, int limit, Duration window) {
    }

    private record Window(long startMillis, int count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Returns 0 if allowed, otherwise the number of seconds until the window resets. */
    public long tryAcquire(Rule rule, String key) {
        long now = clock.millis();
        long windowMillis = rule.window().toMillis();
        Window w = windows.compute(rule.name() + '|' + key, (k, old) ->
                old == null || now - old.startMillis() >= windowMillis
                        ? new Window(now, 1)
                        : new Window(old.startMillis(), old.count() + 1));
        if (w.count() <= rule.limit()) {
            return 0;
        }
        return Math.max(1, (w.startMillis() + windowMillis - now + 999) / 1000);
    }

    public void evictExpired(Duration maxWindow) {
        long cutoff = clock.millis() - maxWindow.toMillis();
        windows.entrySet().removeIf(e -> e.getValue().startMillis() < cutoff);
    }

    int size() {
        return windows.size();
    }
}
