package com.routeiq.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Issue(
        UUID id,
        String tenantId,
        String externalId,
        String title,
        String description,
        String category,
        Map<String, String> attributes,
        IssueStatus status,
        UUID teamId,
        String teamSlug,
        UUID ruleId,
        Instant slaDueAt,
        Instant breachedAt,
        int escalationLevel,
        Instant sourceCreatedAt,
        Instant createdAt,
        Instant routedAt,
        Instant resolvedAt) implements Routable {
}
