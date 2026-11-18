package com.caseware.pendingupdates.domain;

/**
 * Outcome of handling a lineage event (publish or withdrawal).
 *
 * @param pendingChanged KNOWN engagements whose pending target changed (set-based, no downstream load)
 * @param jobsEnqueued   new load jobs for UNKNOWN/STALE engagements
 * @param jobsPromoted   existing open (backfill/reconcile) jobs moved to publish priority instead of duplicated
 */
public record PublishResult(boolean alreadyProcessed, int pendingChanged, int jobsEnqueued, int jobsPromoted) {
    public static PublishResult duplicate() {
        return new PublishResult(true, 0, 0, 0);
    }
}
