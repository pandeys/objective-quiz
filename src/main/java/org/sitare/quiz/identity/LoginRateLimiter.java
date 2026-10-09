package org.sitare.quiz.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** Allows 5 failed student logins per roll number per 10 minutes. */
@Component
public class LoginRateLimiter {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(10);

    private record Window(Instant start, int failures) {
    }

    private final Map<String, Window> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean blocked(String key) {
        Window w = failures.get(normalise(key));
        return w != null && w.failures() >= MAX_FAILURES && clock.instant().isBefore(w.start().plus(WINDOW));
    }

    public void recordFailure(String key) {
        Instant now = clock.instant();
        failures.compute(normalise(key), (k, w) ->
                w == null || !now.isBefore(w.start().plus(WINDOW)) ? new Window(now, 1) : new Window(w.start(), w.failures() + 1));
    }

    public void reset(String key) {
        failures.remove(normalise(key));
    }

    private static String normalise(String key) {
        return key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
    }
}
