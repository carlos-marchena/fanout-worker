package com.caseware.pendingupdates.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A claimed unit of slow work: one ~1 minute engagement load. claimToken fences workers whose lease expired.
 *
 * <p>{@code templateId}, {@code branchId} and {@code targetVersionId} are nullable: a backfill job may exist
 * for an engagement whose template is not known yet. {@code targetVersionId} is informational only; the
 * pending target is always recomputed from the lineage when the job completes.
 */
public record LoadJob(String jobId, String triggerId, String engagementId, String firmId,
                      String templateId, String branchId, String targetVersionId, JobPriority priority,
                      JobStatus status, int attempts, Instant notBefore, String claimToken) {
    public LoadJob {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(triggerId, "triggerId");
        Objects.requireNonNull(engagementId, "engagementId");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(notBefore, "notBefore");
        if (attempts < 0) throw new IllegalArgumentException("attempts must be >= 0");
    }
}
