package com.caseware.pendingupdates.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.caseware.pendingupdates.domain.*;
import com.caseware.pendingupdates.ports.*;
import com.caseware.pendingupdates.testsupport.*;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Executable specification of the ProjectionRepository contract. Runs against the in-memory fake here;
 * the same scenarios must be run against PostgreSQL (Testcontainers) for the production adapter.
 */
class ProjectionContractTest {
    static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    static final Duration LEASE = Duration.ofMinutes(5);
    InMemoryProjectionRepository repo;

    static TemplateVersionPublished publish(String id, String version, String parent) {
        return new TemplateVersionPublished("evt-" + id, id, "tpl", "us", version, parent, NOW);
    }

    @BeforeEach
    void lineage() {
        repo = new InMemoryProjectionRepository();
        repo.seedVersion("tpl", "us", "v3", null);
        repo.seedVersion("tpl", "us", "v4", "v3");
        repo.seedVersion("tpl", "us", "v5", "v4");
        repo.seedVersion("tpl", "us", "v6", "v5");
    }

    // ------------------------------------------------------------------ publish path

    @Test
    void publishIsIdempotentAndEnqueuesOnlyUnknownAndStale() {
        repo.seedKnown("a", "firm-a", "tpl", "us", "v3", null, 1);
        repo.seedUnknown("b", "firm-b", "tpl", null);
        repo.seedStale("c", "firm-c", "tpl", "us", "v2", 4);
        var first = repo.markKnownAndEnqueueUnknown(publish("p7", "v7", "v6"));
        var duplicate = repo.markKnownAndEnqueueUnknown(publish("p7", "v7", "v6"));
        assertEquals(1, first.pendingChanged());
        assertEquals(2, first.jobsEnqueued());
        assertTrue(duplicate.alreadyProcessed());
        assertEquals(2, repo.jobCount());
        assertEquals("v7", repo.pendingTarget("a"));
    }

    @Test
    void outOfOrderPublishesConvergeOnTheNewestTarget() {
        repo.seedKnown("a", "firm", "tpl", "us", "v3", null, 1);
        repo.markKnownAndEnqueueUnknown(publish("p8", "v8", "v7"));   // v7 not seen yet: no path from v3
        repo.markKnownAndEnqueueUnknown(publish("p7", "v7", "v6"));   // late arrival connects the lineage
        assertEquals("v8", repo.pendingTarget("a"));
    }

    @Test
    void olderPublishArrivingLastDoesNotRegressTarget() {
        repo.seedKnown("a", "firm", "tpl", "us", "v3", null, 1);
        repo.markKnownAndEnqueueUnknown(publish("p7", "v7", "v6"));
        repo.markKnownAndEnqueueUnknown(publish("p6", "v6", "v5"));   // redelivered/old event
        assertEquals("v7", repo.pendingTarget("a"));
    }

    @Test
    void declinedChangesAreNotReofferedButNewerOnesAre() {
        repo.seedKnown("a", "firm", "tpl", "us", "v3", "v6", 1);
        assertNull(repo.pendingTarget("a"));
        repo.markKnownAndEnqueueUnknown(publish("p7", "v7", "v6"));
        assertEquals("v7", repo.pendingTarget("a"));
    }

    @Test
    void withdrawalRecomputesKnownTargetsAndIsIdempotent() {
        repo.seedKnown("a", "firm", "tpl", "us", "v3", null, 1);
        repo.markKnownAndEnqueueUnknown(publish("p7", "v7", "v6"));
        var w = new TemplateVersionWithdrawn("evt-w", "w7", "tpl", "v7", NOW);
        assertEquals(1, repo.recomputeAfterWithdrawal(w).pendingChanged());
        assertTrue(repo.recomputeAfterWithdrawal(w).alreadyProcessed());
        assertEquals("v6", repo.pendingTarget("a"));
    }

    // ------------------------------------------------------------------ job completion

    @Test
    void endToEndUnknownEngagementGetsFlaggedAfterLoad() throws Exception {
        repo.seedUnknown("e", "firm", "tpl", null);
        repo.markKnownAndEnqueueUnknown(publish("p7", "v7", "v6"));
        var provider = mock(EngagementVersionProvider.class);
        when(provider.load("e")).thenReturn(new EngagementVersion("e", "tpl", "us", "v4", null, 0));
        new LoadJobProcessor(repo, provider, new InMemoryCapacityLimiter(1), WorkerTest.retry(), WorkerMetrics.NOOP,
                Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC), 1, LEASE).pollOnce();
        assertEquals(InMemoryProjectionRepository.SyncState.KNOWN, repo.syncState("e"));   // aggregate 0 == seeded 0
        assertEquals("v7", repo.pendingTarget("e"));
        assertTrue(repo.openJob("e").isEmpty());
    }

    @Test
    void staleSnapshotAfterConcurrentEventKeepsNewerStateAndPending() {
        repo.seedUnknown("e", "firm", "tpl", null);
        repo.markKnownAndEnqueueUnknown(publish("p7", "v7", "v6"));
        var claimed = repo.claimJobs(1, LEASE, NOW).get(0);
        repo.applyEngagementEvent(new EngagementVersion("e", "tpl", "us", "v5", null, 5));  // Opened while loading
        assertTrue(repo.completeJob(claimed, new EngagementVersion("e", "tpl", "us", "v3", null, 3)));
        assertEquals("v7", repo.pendingTarget("e"));
        assertEquals(InMemoryProjectionRepository.SyncState.KNOWN, repo.syncState("e"));
    }

    @Test
    void branchChangeDiscoveredByLoadMovesTheEngagement() {
        repo.seedVersion("tpl", "ca", "v5-ca", "v5");
        repo.seedVersion("tpl", "ca", "v6-ca", "v5-ca");
        repo.seedUnknown("e", "firm", "tpl", null);
        repo.markKnownAndEnqueueUnknown(publish("p7", "v7", "v6"));
        var claimed = repo.claimJobs(1, LEASE, NOW).get(0);
        repo.completeJob(claimed, new EngagementVersion("e", "tpl", "ca", "v5-ca", null, 2));
        assertEquals("ca", repo.branch("e"));
        assertEquals("v6-ca", repo.pendingTarget("e"));
    }

    // ------------------------------------------------------------------ scheduling

    @Test
    void largeFirmCannotStarveSmallFirms() {
        for (int i = 0; i < 10; i++) repo.seedUnknown("big-" + i, "big-firm", "tpl", null);
        for (int i = 0; i < 3; i++) repo.seedUnknown("small-" + i, "small-firm-" + i, "tpl", null);
        repo.markKnownAndEnqueueUnknown(publish("p7", "v7", "v6"));
        Set<String> firms = repo.claimJobs(4, LEASE, NOW).stream().map(LoadJob::firmId).collect(Collectors.toSet());
        assertEquals(Set.of("big-firm", "small-firm-0", "small-firm-1", "small-firm-2"), firms);
    }

    @Test
    void publishPromotesExistingBackfillJobsInsteadOfDuplicatingLoads() {
        repo.seedUnknown("x", "firm-x", "other-template", null);
        for (int i = 0; i < 3; i++) repo.seedUnknown("e" + i, "firm", "tpl", null);
        var backfill = repo.enqueueLoads("backfill-1", JobPriority.BACKFILL, List.of("x", "e0", "e1", "e2"));
        assertEquals(4, backfill.enqueued());
        var published = repo.markKnownAndEnqueueUnknown(publish("p7", "v7", "v6"));
        assertEquals(3, published.jobsPromoted());
        assertEquals(0, published.jobsEnqueued());
        assertEquals(4, repo.jobCount());
        var claimed = repo.claimJobs(3, LEASE, NOW);
        assertTrue(claimed.stream().allMatch(j -> j.priority() == JobPriority.PUBLISH));
        assertEquals(JobPriority.BACKFILL, repo.openJob("x").orElseThrow().priority());
    }

    @Test
    void backfillSkipsKnownAndPrefersRecentlyActiveEngagements() {
        repo.seedKnown("known", "firm", "tpl", "us", "v6", null, 1);
        repo.seedUnknown("dormant", "firm", null, NOW.minus(Duration.ofDays(200)));
        repo.seedUnknown("active", "firm", null, NOW.minus(Duration.ofHours(1)));
        var result = repo.enqueueLoads("backfill-1", JobPriority.BACKFILL, List.of("known", "dormant", "active", "missing"));
        assertEquals(new EnqueueResult(2, 0, 2), result);
        assertEquals("active", repo.claimJobs(1, LEASE, NOW).get(0).engagementId());
    }

    @Test
    void failedJobReleasesTheOpenSlotForTheReconciler() {
        repo.seedUnknown("e", "firm", "tpl", null);
        repo.markKnownAndEnqueueUnknown(publish("p7", "v7", "v6"));
        var claimed = repo.claimJobs(1, LEASE, NOW).get(0);
        assertTrue(repo.markFailed(claimed, "permanent"));
        assertEquals(1, repo.enqueueLoads("reconcile-1", JobPriority.RECONCILE, List.of("e")).enqueued());
    }
}
