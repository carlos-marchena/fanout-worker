package com.caseware.pendingupdates.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.caseware.pendingupdates.domain.*;
import com.caseware.pendingupdates.ports.*;
import com.caseware.pendingupdates.testsupport.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * LoadJobProcessor / PublishCoordinator behaviour: capacity, retries, validation, fencing.
 */
class WorkerTest {
    static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

    static TemplateVersionPublished event() {
        return new TemplateVersionPublished("evt", "pub", "tpl", "us", "v7", "v6", NOW);
    }

    static EngagementVersion loaded(String id, long seq) {
        return new EngagementVersion(id, "tpl", "us", "v3", null, seq);
    }

    static LoadJob job(String id, String firm) {
        return new LoadJob("job-" + id, "pub", id, firm, "tpl", "us", "v7", JobPriority.PUBLISH,
                JobStatus.IN_PROGRESS, 0, NOW, "token-" + id);
    }

    static RetryPolicy retry() {
        return new RetryPolicy(3, Duration.ofSeconds(30), Duration.ofMinutes(30), () -> 0.5);
    }

    static LoadJobProcessor processor(ProjectionRepository repo, EngagementVersionProvider provider, CapacityLimiter limiter, int batch) {
        return new LoadJobProcessor(repo, provider, limiter, retry(), WorkerMetrics.NOOP, Runnable::run,
                Clock.fixed(NOW, ZoneOffset.UTC), batch, Duration.ofMinutes(5));
    }

    @Test
    void oldClaimCannotCompleteAfterLeaseWasReclaimed() {
        var repo = new InMemoryProjectionRepository();
        repo.seedUnknown("e", "firm", "tpl", null);
        repo.markKnownAndEnqueueUnknown(event());
        var old = repo.claimJobs(1, Duration.ofMinutes(2), NOW).get(0);
        var fresh = repo.claimJobs(1, Duration.ofMinutes(2), NOW.plus(Duration.ofMinutes(3))).get(0);
        assertNotEquals(old.claimToken(), fresh.claimToken());
        assertFalse(repo.completeJob(old, loaded("e", 2)));
        assertTrue(repo.completeJob(fresh, loaded("e", 2)));
    }

    @Test
    void coordinatorNeverCallsSlowProvider() {
        var repo = mock(ProjectionRepository.class);
        var provider = mock(EngagementVersionProvider.class);
        when(repo.markKnownAndEnqueueUnknown(any())).thenReturn(new PublishResult(false, 10, 2, 1));
        when(repo.recomputeAfterWithdrawal(any())).thenReturn(new PublishResult(false, 4, 0, 0));
        var coordinator = new PublishCoordinator(repo, WorkerMetrics.NOOP);
        assertEquals(2, coordinator.handle(event()).jobsEnqueued());
        assertEquals(4, coordinator.handle(new TemplateVersionWithdrawn("evt-w", "w1", "tpl", "v7", NOW)).pendingChanged());
        verifyNoInteractions(provider);
    }

    @Test
    void transientFailureSchedulesRetry() throws Exception {
        var repo = mock(ProjectionRepository.class);
        var provider = mock(EngagementVersionProvider.class);
        var limiter = new InMemoryCapacityLimiter(1);
        var job = job("e", "firm");
        when(repo.claimJobs(anyInt(), any(), any())).thenReturn(List.of(job));
        when(provider.load("e")).thenThrow(new EngagementVersionProvider.TransientDownstreamException("timeout", 0));
        assertEquals(1, processor(repo, provider, limiter, 1).pollOnce());
        verify(repo).scheduleRetry(eq(job), eq(NOW.plusSeconds(15)), eq("timeout"));
        assertEquals(1, limiter.available());
    }

    @Test
    void mismatchedTemplateIsTerminalFailure() throws Exception {
        var repo = mock(ProjectionRepository.class);
        var provider = mock(EngagementVersionProvider.class);
        var job = job("e", "firm");
        when(repo.claimJobs(anyInt(), any(), any())).thenReturn(List.of(job));
        when(provider.load("e")).thenReturn(new EngagementVersion("e", "other", "us", "v3", null, 1));
        processor(repo, provider, new InMemoryCapacityLimiter(1), 1).pollOnce();
        verify(repo).markFailed(eq(job), contains("templateId mismatch"));
    }

    @Test
    void branchChangeIsAppliedNotFailed() throws Exception {
        var repo = mock(ProjectionRepository.class);
        var provider = mock(EngagementVersionProvider.class);
        var job = job("e", "firm");
        var movedToCanada = new EngagementVersion("e", "tpl", "ca", "v5-ca", null, 3);
        when(repo.claimJobs(anyInt(), any(), any())).thenReturn(List.of(job));
        when(provider.load("e")).thenReturn(movedToCanada);
        processor(repo, provider, new InMemoryCapacityLimiter(1), 1).pollOnce();
        verify(repo).completeJob(job, movedToCanada);
        verify(repo, never()).markFailed(any(), any());
    }

    @Test
    void backfillJobWithUnknownTemplateIsAccepted() throws Exception {
        var repo = mock(ProjectionRepository.class);
        var provider = mock(EngagementVersionProvider.class);
        var job = new LoadJob("job-e", "backfill-1", "e", "firm", null, null, null, JobPriority.BACKFILL,
                JobStatus.IN_PROGRESS, 0, NOW, "token");
        when(repo.claimJobs(anyInt(), any(), any())).thenReturn(List.of(job));
        when(provider.load("e")).thenReturn(loaded("e", 1));
        processor(repo, provider, new InMemoryCapacityLimiter(1), 1).pollOnce();
        verify(repo).completeJob(eq(job), any());
    }

    @Test
    void capacityBoundsConcurrentDownstreamCalls() throws Exception {
        int budget = 3;
        var limiter = new InMemoryCapacityLimiter(budget);
        var repo = mock(ProjectionRepository.class);
        var provider = mock(EngagementVersionProvider.class);
        var cursor = new AtomicInteger();
        var jobs = new ArrayList<LoadJob>();
        for (int i = 0; i < 30; i++) jobs.add(job("e" + i, "f" + i));
        when(repo.claimJobs(anyInt(), any(), any())).thenAnswer(inv -> {
            int n = inv.getArgument(0);
            int start = cursor.getAndAdd(n);
            return start >= jobs.size() ? List.of() : jobs.subList(start, Math.min(jobs.size(), start + n));
        });
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        when(provider.load(anyString())).thenAnswer(inv -> {
            int now = active.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            Thread.sleep(15);
            active.decrementAndGet();
            return loaded(inv.getArgument(0), 1);
        });
        ExecutorService pool = Executors.newFixedThreadPool(8);
        var processor = new LoadJobProcessor(repo, provider, limiter, retry(), WorkerMetrics.NOOP, pool,
                Clock.fixed(NOW, ZoneOffset.UTC), budget, Duration.ofMinutes(5));
        while (cursor.get() < jobs.size()) {
            processor.pollOnce();
            Thread.sleep(2);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(peak.get() <= budget);
        assertEquals(budget, limiter.available());
        verify(repo, times(30)).completeJob(any(), any());
    }

    @Test
    void retryBackoffHonorsRetryAfter() {
        var p = new RetryPolicy(3, Duration.ofSeconds(30), Duration.ofMinutes(30), () -> 0.5);
        assertEquals(Duration.ofSeconds(120), p.backoff(1, 120_000));
    }
}
