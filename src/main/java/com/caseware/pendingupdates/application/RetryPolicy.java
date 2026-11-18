package com.caseware.pendingupdates.application;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

public final class RetryPolicy {
    private final int maxAttempts;
    private final Duration base, cap;
    private final DoubleSupplier random;

    public RetryPolicy(int maxAttempts, Duration base, Duration cap, DoubleSupplier random) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        this.base = positive(base, "base");
        this.cap = positive(cap, "cap");
        if (cap.compareTo(base) < 0) throw new IllegalArgumentException("cap must be >= base");
        this.maxAttempts = maxAttempts;
        this.random = Objects.requireNonNull(random);
    }

    private static Duration positive(Duration d, String n) {
        Objects.requireNonNull(d, n);
        if (d.isNegative() || d.isZero()) throw new IllegalArgumentException(n + " must be positive");
        return d;
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(6, Duration.ofSeconds(30), Duration.ofMinutes(30), () -> ThreadLocalRandom.current().nextDouble());
    }

    public boolean isExhausted(int attemptsSoFar) {
        return attemptsSoFar >= maxAttempts;
    }

    /**
     * Full jitter in [0, exponential ceiling], never earlier than Retry-After.
     */
    public Duration backoff(int attemptsSoFar, long retryAfterMillis) {
        if (attemptsSoFar < 1) throw new IllegalArgumentException("attemptsSoFar must be >= 1");
        double sample = random.getAsDouble();
        if (!Double.isFinite(sample) || sample < 0 || sample >= 1)
            throw new IllegalStateException("random must return [0,1)");
        long baseMs = base.toMillis(), capMs = cap.toMillis();
        int shift = Math.min(attemptsSoFar - 1, 62);
        long ceiling = baseMs > (capMs >> shift) ? capMs : Math.min(capMs, baseMs << shift);
        long jitter = (long) (sample * ceiling);
        return Duration.ofMillis(Math.max(jitter, Math.max(0, retryAfterMillis)));
    }
}
