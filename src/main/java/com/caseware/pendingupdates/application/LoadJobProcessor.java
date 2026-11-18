package com.caseware.pendingupdates.application;

import com.caseware.pendingupdates.domain.*;
import com.caseware.pendingupdates.ports.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Bounded fan-out processor. A claim token fences stale workers after lease reclamation.
 */
public final class LoadJobProcessor {
    private final ProjectionRepository repository;
    private final EngagementVersionProvider provider;
    private final CapacityLimiter limiter;
    private final RetryPolicy retryPolicy;
    private final WorkerMetrics metrics;
    private final Executor executor;
    private final Clock clock;
    private final int maxBatch;
    private final Duration lease;

    public LoadJobProcessor(ProjectionRepository repository, EngagementVersionProvider provider, CapacityLimiter limiter,
                            RetryPolicy retryPolicy, WorkerMetrics metrics, Executor executor, Clock clock, int maxBatch, Duration lease) {
        this.repository = Objects.requireNonNull(repository);
        this.provider = Objects.requireNonNull(provider);
        this.limiter = Objects.requireNonNull(limiter);
        this.retryPolicy = Objects.requireNonNull(retryPolicy);
        this.metrics = Objects.requireNonNull(metrics);
        this.executor = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
        if (maxBatch < 1) throw new IllegalArgumentException("maxBatch must be >= 1");
        if (lease.compareTo(Duration.ofMinutes(2)) < 0)
            throw new IllegalArgumentException("lease must exceed expected downstream call duration");
        this.maxBatch = maxBatch;
        this.lease = lease;
    }

    public int pollOnce() {
        List<CapacityLimiter.Permit> permits = acquire(maxBatch);
        if (permits.isEmpty()) {
            metrics.capacityExhausted();
            return 0;
        }
        List<LoadJob> jobs;
        try {
            jobs = repository.claimJobs(permits.size(), lease, clock.instant());
        } catch (RuntimeException e) {
            permits.forEach(CapacityLimiter.Permit::close);
            throw e;
        }
        for (int i = jobs.size(); i < permits.size(); i++) permits.get(i).close();
        int dispatched = 0;
        for (int i = 0; i < jobs.size(); i++) {
            LoadJob job = jobs.get(i);
            CapacityLimiter.Permit permit = permits.get(i);
            try {
                executor.execute(() -> process(job, permit));
                dispatched++;
            } catch (RejectedExecutionException e) {
                permit.close(); /* lease safely expires */
            }
        }
        return dispatched;
    }

    void process(LoadJob job, CapacityLimiter.Permit permit) {
        long started = clock.millis();
        try (permit) {
            EngagementVersion loaded = provider.load(job.engagementId());
            validateLoaded(job, loaded);
            if (repository.completeJob(job, loaded)) metrics.jobSucceeded(Math.max(0, clock.millis() - started));
            // false means this claim was fenced out; do not mutate a newer claim.
        } catch (EngagementVersionProvider.TransientDownstreamException e) {
            retryOrFail(job, e.getMessage(), e.retryAfterMillis());
        } catch (EngagementVersionProvider.PermanentDownstreamException e) {
            fail(job, e.getMessage());
        } catch (IllegalArgumentException e) {
            fail(job, "invalid downstream response: " + e.getMessage());
        } catch (RuntimeException e) {
            retryOrFail(job, e.toString(), 0);
        }
    }

    /**
     * Rejects responses that cannot belong to this job. The branch is deliberately NOT checked: an engagement
     * may have moved to another market branch, which is new information to apply, not a failure.
     */
    private static void validateLoaded(LoadJob job, EngagementVersion loaded) {
        if (loaded == null) throw new IllegalArgumentException("downstream returned null");
        if (!job.engagementId().equals(loaded.engagementId()))
            throw new IllegalArgumentException("engagementId mismatch");
        if (job.templateId() != null && !job.templateId().equals(loaded.templateId()))
            throw new IllegalArgumentException("templateId mismatch");
    }

    private void retryOrFail(LoadJob job, String reason, long retryAfter) {
        int attempts = job.attempts() + 1;
        if (retryPolicy.isExhausted(attempts)) {
            fail(job, "retries exhausted: " + reason);
            return;
        }
        boolean updated = repository.scheduleRetry(job, clock.instant().plus(retryPolicy.backoff(attempts, retryAfter)), reason);
        if (updated) metrics.jobRetried(reason);
    }

    private void fail(LoadJob job, String reason) {
        if (repository.markFailed(job, reason)) metrics.jobFailed(reason);
    }

    private List<CapacityLimiter.Permit> acquire(int max) {
        List<CapacityLimiter.Permit> result = new ArrayList<>(max);
        while (result.size() < max) {
            Optional<CapacityLimiter.Permit> p = limiter.tryAcquire();
            if (p.isEmpty()) break;
            result.add(p.get());
        }
        return result;
    }
}
