package com.caseware.pendingupdates.application;

import com.caseware.pendingupdates.domain.*;
import com.caseware.pendingupdates.ports.*;

import java.util.Objects;

/**
 * Fast path for lineage events. Never calls the slow Engagement Management load: KNOWN engagements are
 * recomputed set-based, UNKNOWN/STALE ones become prioritised load jobs.
 */
public final class PublishCoordinator {
    private final ProjectionRepository repository;
    private final WorkerMetrics metrics;

    public PublishCoordinator(ProjectionRepository repository, WorkerMetrics metrics) {
        this.repository = Objects.requireNonNull(repository);
        this.metrics = Objects.requireNonNull(metrics);
    }

    public PublishResult handle(TemplateVersionPublished event) {
        PublishResult result = repository.markKnownAndEnqueueUnknown(event);
        metrics.publishHandled(result.alreadyProcessed(), result.pendingChanged(), result.jobsEnqueued(), result.jobsPromoted());
        return result;
    }

    public PublishResult handle(TemplateVersionWithdrawn event) {
        PublishResult result = repository.recomputeAfterWithdrawal(event);
        metrics.withdrawalHandled(result.alreadyProcessed(), result.pendingChanged());
        return result;
    }
}
