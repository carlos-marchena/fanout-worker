package com.caseware.pendingupdates.ports;

import com.caseware.pendingupdates.domain.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Persistence boundary of the regional projection (Aurora PostgreSQL). Every mutating operation is one
 * short transaction. The in-memory fake in the tests is the executable specification of this contract;
 * the same scenarios must pass against PostgreSQL (Testcontainers) before production use.
 *
 * <h2>Invariants</h2>
 * <ol>
 *   <li><b>Pending is derived, never copied from an event.</b> Whenever an engagement is KNOWN, its pending
 *       target equals {@link VersionGraph#resolvePendingTarget} for its (branch, applied, declinedThrough)
 *       over the template's current lineage. Every operation that changes either side (publish, withdrawal,
 *       job completion, engagement events) recomputes it. Hence out-of-order publishes converge, a withdrawn
 *       target falls back, and a decline is never re-offered for the same changes.</li>
 *   <li><b>Snapshot ordering.</b> A loaded snapshot is applied iff its aggregateVersion is greater than the
 *       stored one, or equal while the row is not KNOWN (confirms an UNKNOWN/STALE row). Older snapshots are
 *       ignored, but the pending target is still recomputed for a KNOWN row.</li>
 *   <li><b>At most one open job per engagement</b> (PENDING or IN_PROGRESS; partial unique index). Enqueueing
 *       an engagement with an open PENDING job of lower priority promotes it instead of adding a second
 *       ~1 minute load. An IN_PROGRESS job is left alone: its completion recomputes against the latest lineage.</li>
 *   <li><b>Fencing.</b> completeJob/scheduleRetry/markFailed apply only if job id AND claimToken match the
 *       current IN_PROGRESS claim; otherwise they return false and change nothing.</li>
 * </ol>
 */
public interface ProjectionRepository {
    /**
     * Atomic and idempotent on publishId: record the lineage edge, recompute the pending target of KNOWN
     * engagements of the template (set-based, no downstream call), and enqueue or promote to
     * {@link JobPriority#PUBLISH} a job for each UNKNOWN/STALE engagement of the template.
     * Engagements whose template is not yet known are left to backfill.
     */
    PublishResult markKnownAndEnqueueUnknown(TemplateVersionPublished event);

    /**
     * Atomic and idempotent on withdrawalId: flag the version withdrawn (never delete it) and recompute the
     * pending target of KNOWN engagements of the template. Open jobs need no change (invariant 1).
     */
    PublishResult recomputeAfterWithdrawal(TemplateVersionWithdrawn event);

    /**
     * Backfill and Reconciler entry point: enqueue or promote load jobs for existing projection rows.
     * {@link JobPriority#BACKFILL} skips rows already KNOWN; {@link JobPriority#RECONCILE} re-checks them.
     */
    EnqueueResult enqueueLoads(String triggerId, JobPriority priority, Collection<String> engagementIds);

    /**
     * Claims up to {@code max} due jobs (PENDING with notBefore <= now, or IN_PROGRESS with an expired lease)
     * using SKIP LOCKED, with a fresh claimToken and lease per claim. Order: priority, then round-robin across
     * firms (rank of the job inside its firm and priority), then most recently active engagement, then age.
     */
    List<LoadJob> claimJobs(int max, Duration lease, Instant now);

    /**
     * Applies the snapshot per invariants 1-2 (including a branch change) and closes the job. Fenced.
     */
    boolean completeJob(LoadJob job, EngagementVersion loaded);

    /**
     * Back to PENDING with attempts + 1 and the given notBefore; the job stays the engagement's open job. Fenced.
     */
    boolean scheduleRetry(LoadJob job, Instant notBefore, String reason);

    /**
     * Terminal FAILED (alarm + Reconciler); releases the open-job slot. Fenced.
     */
    boolean markFailed(LoadJob job, String reason);
}
