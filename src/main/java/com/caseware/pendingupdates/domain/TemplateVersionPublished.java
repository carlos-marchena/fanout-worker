package com.caseware.pendingupdates.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Validated inbound event from the Template Store outbox. {@code publishId} is the business idempotency
 * key for a fan-out (stable across outbox redeliveries); {@code eventId} identifies the envelope and is
 * kept for tracing. {@code parentVersionId} carries the lineage edge (null only for a root version) so the
 * regional lineage replica can be maintained from events alone.
 */
public record TemplateVersionPublished(String eventId, String publishId, String templateId,
                                       String branchId, String versionId, String parentVersionId,
                                       Instant publishedAt) {
    public TemplateVersionPublished {
        requireText(eventId, "eventId");
        requireText(publishId, "publishId");
        requireText(templateId, "templateId");
        requireText(branchId, "branchId");
        requireText(versionId, "versionId");
        if (parentVersionId != null && parentVersionId.isBlank())
            throw new IllegalArgumentException("parentVersionId must be null or non-blank");
        Objects.requireNonNull(publishedAt, "publishedAt");
    }

    static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
