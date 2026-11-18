package com.caseware.pendingupdates.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A published version was withdrawn. Withdrawal is per version (not inherited by descendants) and
 * recomputes targets through the same path as a publish. {@code withdrawalId} is the idempotency key.
 */
public record TemplateVersionWithdrawn(String eventId, String withdrawalId, String templateId,
                                       String versionId, Instant withdrawnAt) {
    public TemplateVersionWithdrawn {
        TemplateVersionPublished.requireText(eventId, "eventId");
        TemplateVersionPublished.requireText(withdrawalId, "withdrawalId");
        TemplateVersionPublished.requireText(templateId, "templateId");
        TemplateVersionPublished.requireText(versionId, "versionId");
        Objects.requireNonNull(withdrawnAt, "withdrawnAt");
    }
}
