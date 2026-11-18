package com.caseware.pendingupdates.testsupport;

import com.caseware.pendingupdates.domain.*;
import com.caseware.pendingupdates.ports.ProjectionRepository;

import java.time.*;
import java.util.*;

/**
 * Synchronized behavioural fake and executable specification of {@link ProjectionRepository}.
 * Not a substitute for PostgreSQL integration tests (locking, SKIP LOCKED, partial unique index).
 */
public final class InMemoryProjectionRepository implements ProjectionRepository {
    public enum SyncState {KNOWN, UNKNOWN, STALE}

    private static final class Projection {
        final String id, firm;
        String template, branch, applied, declinedThrough, pending;
        SyncState sync = SyncState.UNKNOWN;
        long aggregate;
        Instant lastActive;

        Projection(String id, String firm) {
            this.id = id;
            this.firm = firm;
        }
    }

    private static final class Row {
        final String jobId, trigger, engagement, firm, template, branch, target;
        final long seq;
        final Instant lastActive;
        JobPriority priority;
        JobStatus status = JobStatus.PENDING;
        int attempts;
        Instant due = Instant.MIN, lease = Instant.MIN;
        String token;

        Row(long seq, String trigger, Projection p, JobPriority priority, String target) {
            this.seq = seq;
            this.jobId = "job-" + seq;
            this.trigger = trigger;
            this.engagement = p.id;
            this.firm = p.firm;
            this.template = p.template;
            this.branch = p.branch;
            this.target = target;
            this.lastActive = p.lastActive;
            this.priority = priority;
        }
    }

    private enum Outcome {ENQUEUED, PROMOTED, ALREADY_OPEN}

    private final Map<String, Projection> projections = new LinkedHashMap<>();
    private final Map<String, Row> jobs = new LinkedHashMap<>();
    private final Map<String, Row> openByEngagement = new HashMap<>();
    private final Map<String, VersionGraph> graphs = new HashMap<>();
    private final Set<String> processedTriggers = new HashSet<>();
    private long sequence;

    // ---------------------------------------------------------------- seeding (test setup only)

    public synchronized void seedVersion(String template, String branch, String version, String parent) {
        graph(template).add(new VersionGraph.Version(version, branch, parent, false));
    }

    public synchronized void seedKnown(String id, String firm, String template, String branch,
                                       String applied, String declinedThrough, long aggregate) {
        Projection p = new Projection(id, firm);
        p.template = template;
        p.branch = branch;
        p.applied = applied;
        p.declinedThrough = declinedThrough;
        p.aggregate = aggregate;
        p.sync = SyncState.KNOWN;
        projections.put(id, p);
        recompute(p);
    }

    /**
     * {@code template} may be null: the engagement was listed but its template is not known yet.
     */
    public synchronized void seedUnknown(String id, String firm, String template, Instant lastActive) {
        Projection p = new Projection(id, firm);
        p.template = template;
        p.lastActive = lastActive;
        projections.put(id, p);
    }

    public synchronized void seedStale(String id, String firm, String template, String branch, String applied, long aggregate) {
        Projection p = new Projection(id, firm);
        p.template = template;
        p.branch = branch;
        p.applied = applied;
        p.aggregate = aggregate;
        p.sync = SyncState.STALE;
        projections.put(id, p);
    }

    /**
     * Mirrors the engagement-event consumer (Opened/Applied/Declined): newer-wins, then recompute.
     */
    public synchronized void applyEngagementEvent(EngagementVersion v) {
        Projection p = projections.get(v.engagementId());
        if (p != null) applySnapshot(p, v);
    }

    // ---------------------------------------------------------------- contract

    @Override
    public synchronized PublishResult markKnownAndEnqueueUnknown(TemplateVersionPublished e) {
        if (!processedTriggers.add("publish:" + e.publishId())) return PublishResult.duplicate();
        graph(e.templateId()).add(new VersionGraph.Version(e.versionId(), e.branchId(), e.parentVersionId(), false));
        int changed = 0, enqueued = 0, promoted = 0;
        for (Projection p : projections.values()) {
            if (!e.templateId().equals(p.template)) continue;
            if (p.sync == SyncState.KNOWN) {
                if (recompute(p)) changed++;
            } else {
                Outcome o = enqueue(e.publishId(), p, JobPriority.PUBLISH, e.versionId());
                if (o == Outcome.ENQUEUED) enqueued++;
                else if (o == Outcome.PROMOTED) promoted++;
            }
        }
        return new PublishResult(false, changed, enqueued, promoted);
    }

    @Override
    public synchronized PublishResult recomputeAfterWithdrawal(TemplateVersionWithdrawn e) {
        if (!processedTriggers.add("withdraw:" + e.withdrawalId())) return PublishResult.duplicate();
        graph(e.templateId()).withdraw(e.versionId());
        int changed = 0;
        for (Projection p : projections.values())
            if (e.templateId().equals(p.template) && p.sync == SyncState.KNOWN && recompute(p)) changed++;
        return new PublishResult(false, changed, 0, 0);
    }

    @Override
    public synchronized EnqueueResult enqueueLoads(String triggerId, JobPriority priority, Collection<String> engagementIds) {
        int enqueued = 0, promoted = 0, skipped = 0;
        for (String id : engagementIds) {
            Projection p = projections.get(id);
            if (p == null || (priority == JobPriority.BACKFILL && p.sync == SyncState.KNOWN)) {
                skipped++;
                continue;
            }
            switch (enqueue(triggerId, p, priority, null)) {
                case ENQUEUED -> enqueued++;
                case PROMOTED -> promoted++;
                case ALREADY_OPEN -> skipped++;
            }
        }
        return new EnqueueResult(enqueued, promoted, skipped);
    }

    @Override
    public synchronized List<LoadJob> claimJobs(int max, Duration lease, Instant now) {
        List<Row> due = new ArrayList<>();
        for (Row r : jobs.values())
            if ((r.status == JobStatus.PENDING && !r.due.isAfter(now)) || (r.status == JobStatus.IN_PROGRESS && !r.lease.isAfter(now)))
                due.add(r);
        Comparator<Row> withinFirm = Comparator
                .comparing((Row r) -> r.lastActive, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparingLong(r -> r.seq);
        // Same as SQL: row_number() OVER (PARTITION BY priority, firm_id ORDER BY last_active_at DESC NULLS LAST, created_at)
        Map<String, List<Row>> partitions = new HashMap<>();
        for (Row r : due) partitions.computeIfAbsent(r.priority.rank() + "|" + r.firm, k -> new ArrayList<>()).add(r);
        Map<Row, Integer> firmRank = new IdentityHashMap<>();
        for (List<Row> partition : partitions.values()) {
            partition.sort(withinFirm);
            for (int i = 0; i < partition.size(); i++) firmRank.put(partition.get(i), i + 1);
        }
        due.sort(Comparator.comparingInt((Row r) -> r.priority.rank())
                .thenComparingInt(firmRank::get)
                .thenComparing(withinFirm));
        List<LoadJob> out = new ArrayList<>();
        for (Row r : due.subList(0, Math.min(max, due.size()))) {
            r.status = JobStatus.IN_PROGRESS;
            r.lease = now.plus(lease);
            r.token = UUID.randomUUID().toString();
            out.add(toJob(r));
        }
        return out;
    }

    @Override
    public synchronized boolean completeJob(LoadJob j, EngagementVersion v) {
        Row r = current(j);
        if (r == null) return false;
        Projection p = projections.get(v.engagementId());
        if (p != null) applySnapshot(p, v);
        close(r, JobStatus.DONE);
        return true;
    }

    @Override
    public synchronized boolean scheduleRetry(LoadJob j, Instant due, String reason) {
        Row r = current(j);
        if (r == null) return false;
        r.status = JobStatus.PENDING;
        r.attempts++;
        r.due = due;
        r.token = null;
        return true;
    }

    @Override
    public synchronized boolean markFailed(LoadJob j, String reason) {
        Row r = current(j);
        if (r == null) return false;
        close(r, JobStatus.FAILED);
        return true;
    }

    // ---------------------------------------------------------------- inspection (assertions only)

    public synchronized String pendingTarget(String id) {
        return projections.get(id).pending;
    }

    public synchronized SyncState syncState(String id) {
        return projections.get(id).sync;
    }

    public synchronized String branch(String id) {
        return projections.get(id).branch;
    }

    public synchronized Optional<LoadJob> openJob(String engagementId) {
        return Optional.ofNullable(openByEngagement.get(engagementId)).map(InMemoryProjectionRepository::toJob);
    }

    public synchronized JobStatus status(String jobId) {
        return jobs.get(jobId).status;
    }

    public synchronized int jobCount() {
        return jobs.size();
    }

    // ---------------------------------------------------------------- internals

    private void applySnapshot(Projection p, EngagementVersion v) {
        boolean newer = v.aggregateVersion() > p.aggregate
                || (v.aggregateVersion() == p.aggregate && p.sync != SyncState.KNOWN);
        if (newer) {
            p.template = v.templateId();
            p.branch = v.branchId();
            p.applied = v.appliedVersionId();
            p.declinedThrough = v.declinedThroughVersionId();
            p.aggregate = v.aggregateVersion();
            p.sync = SyncState.KNOWN;
        }
        if (p.sync == SyncState.KNOWN) recompute(p);
    }

    private boolean recompute(Projection p) {
        String before = p.pending;
        VersionGraph g = p.template == null ? null : graphs.get(p.template);
        p.pending = g == null ? null : g.resolvePendingTarget(p.branch, p.applied, p.declinedThrough).orElse(null);
        return !Objects.equals(before, p.pending);
    }

    private Outcome enqueue(String trigger, Projection p, JobPriority priority, String target) {
        Row open = openByEngagement.get(p.id);
        if (open != null) {
            if (open.status == JobStatus.PENDING && priority.outranks(open.priority)) {
                open.priority = priority;
                return Outcome.PROMOTED;
            }
            return Outcome.ALREADY_OPEN;
        }
        Row r = new Row(sequence++, trigger, p, priority, target);
        jobs.put(r.jobId, r);
        openByEngagement.put(p.id, r);
        return Outcome.ENQUEUED;
    }

    private void close(Row r, JobStatus terminal) {
        r.status = terminal;
        r.token = null;
        openByEngagement.remove(r.engagement, r);
    }

    private Row current(LoadJob j) {
        Row r = jobs.get(j.jobId());
        return r != null && r.status == JobStatus.IN_PROGRESS && Objects.equals(r.token, j.claimToken()) ? r : null;
    }

    private VersionGraph graph(String template) {
        return graphs.computeIfAbsent(template, k -> new VersionGraph());
    }

    private static LoadJob toJob(Row r) {
        return new LoadJob(r.jobId, r.trigger, r.engagement, r.firm, r.template, r.branch, r.target,
                r.priority, r.status, r.attempts, r.due, r.token);
    }
}
