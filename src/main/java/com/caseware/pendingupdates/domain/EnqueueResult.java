package com.caseware.pendingupdates.domain;

/**
 * Outcome of enqueueing backfill or reconciler work.
 *
 * @param skipped engagements not enqueued: unknown to the projection, already KNOWN (backfill only),
 *                or already covered by an open job of equal or higher priority
 */
public record EnqueueResult(int enqueued, int promoted, int skipped) {
}
