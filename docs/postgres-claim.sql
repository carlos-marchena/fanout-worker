-- Illustrative PostgreSQL shapes for the ProjectionRepository adapter. Validate plans and indexes with
-- Testcontainers + realistic data before production; the in-memory fake is the behavioural spec.

-- 1. Jobs: at most ONE open job per engagement (a publish promotes a backfill job instead of adding a load).
CREATE TABLE load_jobs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trigger_id      text        NOT NULL,           -- publishId, backfill run id or reconciler sweep id
    engagement_id   text        NOT NULL,
    firm_id         text        NOT NULL,
    template_id     text,                           -- NULL for backfill of an engagement not yet identified
    branch_id       text,
    target_version  text,                           -- informational; target is recomputed on completion
    priority        smallint    NOT NULL,           -- JobPriority.rank(): 0 publish, 1 reconcile, 2 backfill
    last_active_at  timestamptz,
    status          text        NOT NULL DEFAULT 'PENDING',
    attempts        int         NOT NULL DEFAULT 0,
    not_before      timestamptz NOT NULL DEFAULT '-infinity',
    lease_until     timestamptz,
    claim_token     uuid,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX load_jobs_one_open_per_engagement
    ON load_jobs (engagement_id) WHERE status IN ('PENDING', 'IN_PROGRESS');
CREATE INDEX load_jobs_due
    ON load_jobs (priority, firm_id, last_active_at DESC NULLS LAST, created_at)
    WHERE status IN ('PENDING', 'IN_PROGRESS');

-- 2. Enqueue or promote (publish, backfill, reconcile). Same transaction as the processed-trigger insert.
INSERT INTO load_jobs (trigger_id, engagement_id, firm_id, template_id, branch_id, target_version, priority, last_active_at)
SELECT :trigger_id, p.engagement_id, p.firm_id, p.template_id, p.branch_id, :target_version, :priority, p.last_active_at
FROM projection p
WHERE p.template_id = :template_id AND p.sync_state IN ('UNKNOWN', 'STALE')
ON CONFLICT (engagement_id) WHERE status IN ('PENDING', 'IN_PROGRESS')
DO UPDATE SET priority = EXCLUDED.priority
WHERE load_jobs.status = 'PENDING' AND EXCLUDED.priority < load_jobs.priority;

-- 3. Pending targets for KNOWN rows are recomputed, never copied from the event. A template has a few dozen
--    versions, so the adapter loads its lineage, resolves VersionGraph.resolvePendingTarget once per distinct
--    (branch, applied, declined_through) tuple and applies one set-based UPDATE per tuple:
-- UPDATE projection SET pending_target = :resolved
-- WHERE template_id = :template_id AND sync_state = 'KNOWN'
--   AND branch_id = :branch AND applied_version = :applied
--   AND declined_through IS NOT DISTINCT FROM :declined_through
--   AND pending_target IS DISTINCT FROM :resolved;

-- 4. Claim: priority first, then round-robin across firms, then most recently active, then age.
--    Window functions cannot be combined with FOR UPDATE, so rank first and lock second. Under concurrent
--    claimers SKIP LOCKED makes fairness approximate, not exact; bound the candidate set for large backlogs.
BEGIN;
WITH ranked AS (
    SELECT id, priority, last_active_at, created_at,
           row_number() OVER (PARTITION BY priority, firm_id
                              ORDER BY last_active_at DESC NULLS LAST, created_at, id) AS firm_rank
    FROM load_jobs
    WHERE (status = 'PENDING' AND not_before <= :now)
       OR (status = 'IN_PROGRESS' AND lease_until <= :now)
), picked AS (
    SELECT j.id
    FROM load_jobs j
    JOIN ranked r ON r.id = j.id
    ORDER BY r.priority, r.firm_rank, r.last_active_at DESC NULLS LAST, r.created_at, r.id
    LIMIT :batch_size
    FOR UPDATE OF j SKIP LOCKED
)
UPDATE load_jobs j
SET status = 'IN_PROGRESS',
    lease_until = :now + :lease_duration,
    claim_token = gen_random_uuid()            -- fences any worker whose lease expired
FROM picked
WHERE j.id = picked.id
RETURNING j.*;
COMMIT;

-- 5. Every transition is fenced; update count 0 means a stale claim, not success.
-- UPDATE load_jobs SET status = 'DONE', claim_token = NULL
-- WHERE id = :id AND status = 'IN_PROGRESS' AND claim_token = :claim_token;
-- Completion applies the snapshot in the same transaction:
--   aggregate_version > stored, or = stored while sync_state <> 'KNOWN'  -> overwrite (incl. branch), KNOWN;
--   then recompute pending_target for the row if KNOWN (step 3).
