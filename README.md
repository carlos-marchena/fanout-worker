# CaseWare Template Publish Fan-out Worker

Java 17 / Maven implementation: the template-publish fan-out worker from the [Pending Template Updates design document](docs/Pending%20Template%20Updates%20-%20Design%20Document.pdf).
Plain Java and ports; no Spring Boot, not even for wiring. This is not a deployable service (see Scope).

## Design

- `PublishCoordinator`: fast path for `TemplateVersionPublished` / `TemplateVersionWithdrawn`. It never calls the
  slow load. One transaction records the event (idempotent on `publishId` / `withdrawalId`), recomputes the
  pending target of **KNOWN** engagements with set-based updates, and enqueues or promotes jobs for **UNKNOWN/STALE** ones.
- `LoadJobProcessor`: acquires regional capacity permits *before* claiming the same number of jobs, calls
  Engagement Management (~1 min) outside any DB transaction on a bounded executor, then completes, retries or fails the job.
- `VersionGraph` (domain, pure): the single definition of "what is pending". It covers accumulation (v5+v6+v7 give one
  target), declines (`declinedThrough` pins, newer versions are offered again), per-version withdrawal, market
  branches, and refuses to guess on ambiguous forks.
- `ProjectionRepository`: the persistence contract, with its invariants in the Javadoc. `ProjectionContractTest` is its
  executable spec; the SQL shapes are in `docs/postgres-claim.sql`.

## Key tradeoffs

| Decision | Why | Cost |
|---|---|---|
| Pending target is **recomputed from lineage**, never copied from the event | Out-of-order publishes converge, withdrawals fall back, a decline is never re-offered, and races between a load and an `Opened` event cannot lose the flag | The adapter must load the template's lineage (small: dozens of versions) |
| **At-least-once** with leases and a fresh `claimToken` per claim (fencing) | Crash recovery without coordination; a slow, expired worker cannot overwrite a newer claim | A ~1 min load can run twice; loads are reads, so this is safe |
| **One open job per engagement**; a publish *promotes* a backfill job | A publish moves its template's unknown engagements to the front without spending the other team's capacity twice | Partial unique index and an `ON CONFLICT` promote path |
| Claim order: priority → round-robin by firm → most recently active → age | The 40,000-engagement firm cannot starve 40-engagement firms; publishes beat backfill | Fair only approximately under concurrent `SKIP LOCKED` claimers |
| Permits acquired **before** claiming | No job is leased that cannot be started, so leases don't expire in a queue | A shared regional limiter is required (the test one is JVM-local) |
| Branch change in a loaded snapshot is **applied**, not failed | Engagements can move between market branches | Only engagement and template mismatches are terminal |
| Retries: capped exponential full jitter, honours `Retry-After`; then `FAILED` | Protects a downstream with limited capacity | Exhausted jobs need the alarm and the Reconciler (`enqueueLoads(RECONCILE)`) |

## Scope and limitations

Only application code and contracts are included. Concrete Aurora, SQS and HTTP adapters, the distributed limiter,
migrations, the engagement-event consumer, the Reconciler and summaries are out of scope. The in-memory repository is
a synchronized behavioural fake: it does not prove PostgreSQL locking. Before production, run `ProjectionContractTest`
against PostgreSQL with Testcontainers, adding cases for `SKIP LOCKED`, the partial unique index and lease reclamation.
The capacity budget (50 concurrent loads, split by region) must be enforced by a shared regional leased-permit store
across all ECS tasks.

## AI usage

I used AI as an engineering accelerator to complete the challenge within the available time, while retaining ownership of the architecture, technical decisions, and final implementation.

- **How I used it:** To accelerate Java scaffolding, define application boundaries and interfaces, explore retry/backoff implementation details, generate test scenarios, and review the implementation against the design document.
- **Engineering ownership:** I iteratively reviewed and refined the implementation, validating it against the system's core invariants, particularly version ordering, branch lineage, decline semantics, concurrency, idempotency, and regional capacity constraints. Some initial suggestions required adjustments to align with the intended design. For example, an early version copied the pending target from the publish event, the claim query did not adequately address fair workload distribution across firms, and a branch change was treated as a failure. These issues were identified through design-level analysis and were not covered by the initial generated tests.
- **Validation:** I used the design requirements and expected system behavior as the primary validation criteria, rather than relying solely on generated tests. I also reviewed implementation details and challenged assumptions wherever correctness, scalability, or concurrency could be affected.
- **Guidance for other engineers:** give the AI the invariants (the `ProjectionRepository` Javadoc and the version rules) and have it write the failing scenario first; review concurrency, SQL and locking by hand.
- **Where not to trust it:** version and decline semantics, capacity and cost arithmetic, and anything that must be defended in a regulatory review; generated tests tend to confirm the code rather than the design.
- **Takeaway:** AI helped reduce repetitive implementation work and accelerate technical exploration, allowing me to dedicate more time to architectural trade-offs, edge cases, correctness, and overall solution quality.

## Run

Requirements: JDK 17 and Maven 3.6.3+. Maven must run on JDK 17 (check the `Java version` line of `mvn -version`;
if it shows an older JDK, set `JAVA_HOME` to a JDK 17).

```bash
java -version
mvn -version
mvn clean test
```
