package com.caseware.pendingupdates.testsupport;

import com.caseware.pendingupdates.ports.CapacityLimiter;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test-only JVM-local limiter; production must use a shared regional permit store.
 */
public final class InMemoryCapacityLimiter implements CapacityLimiter {
    private final Semaphore semaphore;

    public InMemoryCapacityLimiter(int permits) {
        semaphore = new Semaphore(permits);
    }

    public Optional<Permit> tryAcquire() {
        if (!semaphore.tryAcquire()) return Optional.empty();
        AtomicBoolean released = new AtomicBoolean();
        return Optional.of(() -> {
            if (released.compareAndSet(false, true)) semaphore.release();
        });
    }

    public int available() {
        return semaphore.availablePermits();
    }
}
