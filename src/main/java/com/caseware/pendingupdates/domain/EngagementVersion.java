package com.caseware.pendingupdates.domain;

/**
 * Template state of one engagement as reported by Engagement Management (snapshot or event).
 * {@code declinedThroughVersionId} is nullable (nothing declined). {@code aggregateVersion} orders
 * snapshots and events of the same engagement; timestamps are never used for ordering.
 */
public record EngagementVersion(String engagementId, String templateId, String branchId,
                                String appliedVersionId, String declinedThroughVersionId, long aggregateVersion) {
    public EngagementVersion {
        if (engagementId == null || engagementId.isBlank()) throw new IllegalArgumentException("engagementId required");
        if (templateId == null || templateId.isBlank()) throw new IllegalArgumentException("templateId required");
        if (branchId == null || branchId.isBlank()) throw new IllegalArgumentException("branchId required");
        if (appliedVersionId == null || appliedVersionId.isBlank())
            throw new IllegalArgumentException("appliedVersionId required");
        if (aggregateVersion < 0) throw new IllegalArgumentException("aggregateVersion must be >= 0");
    }
}
