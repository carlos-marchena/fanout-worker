package com.caseware.pendingupdates.ports;

import com.caseware.pendingupdates.domain.EngagementVersion;

public interface EngagementVersionProvider {
    EngagementVersion load(String engagementId) throws TransientDownstreamException, PermanentDownstreamException;

    final class TransientDownstreamException extends Exception {
        private final long retryAfterMillis;

        public TransientDownstreamException(String message, long retryAfterMillis) {
            super(message);
            this.retryAfterMillis = Math.max(0, retryAfterMillis);
        }

        public long retryAfterMillis() {
            return retryAfterMillis;
        }
    }

    final class PermanentDownstreamException extends Exception {
        public PermanentDownstreamException(String message) {
            super(message);
        }
    }
}
