package com.caseware.pendingupdates.domain;

import java.util.*;

/**
 * Version lineage of ONE product template (all market branches). Pure, deterministic and
 * not thread-safe; callers synchronise. It is the single definition of "what is pending":
 * the PostgreSQL adapter must produce exactly these results (a template has only a few dozen
 * versions per year, so loading its graph and resolving in Java is a valid implementation).
 *
 * <p>Rules (Part 1, section 2):
 * <ul>
 *   <li>The pending target is the deepest non-withdrawn descendant of the applied version that
 *       lives on the engagement's branch. Accumulated v5, v6, v7 therefore produce ONE target, v7.</li>
 *   <li>Withdrawal is per version, not inherited: v8 whose parent v7 was withdrawn is still offered.</li>
 *   <li>A decline pins {@code declinedThrough}; it never changes the applied version. A target equal
 *       to, or an ancestor of, {@code declinedThrough} is not offered again; anything newer is.</li>
 *   <li>Versions are never ordered by timestamp or by lexical/numeric id, only by lineage depth.</li>
 *   <li>No path, unknown applied version, or two equally deep tips on the branch: no target
 *       (the case is escalated to the content team instead of guessing).</li>
 * </ul>
 */
public final class VersionGraph {
    public record Version(String versionId, String branchId, String parentVersionId, boolean withdrawn) {
        public Version {
            if (versionId == null || versionId.isBlank()) throw new IllegalArgumentException("versionId required");
            if (branchId == null || branchId.isBlank()) throw new IllegalArgumentException("branchId required");
        }
    }

    private final Map<String, Version> versions = new HashMap<>();
    private final Map<String, List<String>> children = new HashMap<>();
    private final Set<String> withdrawnBeforePublish = new HashSet<>();

    /**
     * Idempotent: re-adding a known version (redelivered publish) changes nothing, never un-withdraws.
     */
    public void add(Version v) {
        if (versions.containsKey(v.versionId())) return;
        boolean withdrawn = v.withdrawn() || withdrawnBeforePublish.remove(v.versionId());
        versions.put(v.versionId(), new Version(v.versionId(), v.branchId(), v.parentVersionId(), withdrawn));
        if (v.parentVersionId() != null)
            children.computeIfAbsent(v.parentVersionId(), k -> new ArrayList<>()).add(v.versionId());
    }

    /**
     * Idempotent. Withdrawing a version whose publish has not arrived yet (events out of order) is
     * remembered, so the late publish is recorded as withdrawn.
     */
    public void withdraw(String versionId) {
        Version v = versions.get(versionId);
        if (v == null) {
            withdrawnBeforePublish.add(versionId);
            return;
        }
        versions.put(versionId, new Version(v.versionId(), v.branchId(), v.parentVersionId(), true));
    }

    public boolean contains(String versionId) {
        return versions.containsKey(versionId);
    }

    public Optional<String> resolvePendingTarget(String branchId, String appliedVersionId, String declinedThroughVersionId) {
        if (branchId == null || appliedVersionId == null || !versions.containsKey(appliedVersionId))
            return Optional.empty();
        String best = null;
        int bestDepth = 0;
        boolean ambiguous = false;
        Deque<Map.Entry<String, Integer>> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>(Set.of(appliedVersionId));
        queue.add(Map.entry(appliedVersionId, 0));
        while (!queue.isEmpty()) {
            var current = queue.poll();
            for (String childId : children.getOrDefault(current.getKey(), List.of())) {
                if (!visited.add(childId)) continue;
                int depth = current.getValue() + 1;
                queue.add(Map.entry(childId, depth));
                Version child = versions.get(childId);
                if (child == null || child.withdrawn() || !child.branchId().equals(branchId)) continue;
                if (depth > bestDepth) {
                    best = childId;
                    bestDepth = depth;
                    ambiguous = false;
                } else if (depth == bestDepth) {
                    ambiguous = true;
                }
            }
        }
        if (best == null || ambiguous) return Optional.empty();
        if (declinedThroughVersionId != null && isSameOrAncestor(best, declinedThroughVersionId))
            return Optional.empty();
        return Optional.of(best);
    }

    private boolean isSameOrAncestor(String candidate, String descendant) {
        Set<String> seen = new HashSet<>();
        for (String id = descendant; id != null && seen.add(id); ) {
            if (id.equals(candidate)) return true;
            Version v = versions.get(id);
            id = v == null ? null : v.parentVersionId();
        }
        return false;
    }
}
