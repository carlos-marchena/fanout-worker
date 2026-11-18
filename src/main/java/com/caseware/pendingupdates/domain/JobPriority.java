package com.caseware.pendingupdates.domain;

/**
 * Claim order across job sources. {@link #rank()} is the value stored in {@code load_jobs.priority};
 * lower is claimed first. A publish moves its template's unknown engagements to the front.
 */
public enum JobPriority {
    PUBLISH(0), RECONCILE(1), BACKFILL(2);

    private final int rank;

    JobPriority(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean outranks(JobPriority other) {
        return rank < other.rank;
    }
}
