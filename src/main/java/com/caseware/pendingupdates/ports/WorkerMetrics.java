package com.caseware.pendingupdates.ports;

public interface WorkerMetrics {
    void publishHandled(boolean duplicate, int pendingChanged, int enqueued, int promoted);

    void withdrawalHandled(boolean duplicate, int pendingChanged);

    void jobSucceeded(long millis);

    void jobRetried(String reason);

    void jobFailed(String reason);

    void capacityExhausted();

    WorkerMetrics NOOP = new WorkerMetrics() {
        public void publishHandled(boolean d, int c, int e, int p) {
        }

        public void withdrawalHandled(boolean d, int c) {
        }

        public void jobSucceeded(long m) {
        }

        public void jobRetried(String r) {
        }

        public void jobFailed(String r) {
        }

        public void capacityExhausted() {
        }
    };
}
