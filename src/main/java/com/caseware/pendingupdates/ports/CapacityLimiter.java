package com.caseware.pendingupdates.ports;

import java.util.Optional;

/**
 * Production implementation must coordinate leased permits across ALL regional JVMs.
 */
public interface CapacityLimiter {
    Optional<Permit> tryAcquire();

    interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
