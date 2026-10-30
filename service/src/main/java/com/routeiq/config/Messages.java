package com.routeiq.config;

import java.time.Instant;
import java.util.UUID;

/** Payloads carried on the Kafka topics. All messages are keyed by issue id. */
public final class Messages {

    public record IssueCreated(UUID issueId, String tenantId, Instant createdAt) {
    }

    public record IssueRouted(UUID issueId, String tenantId, UUID teamId, UUID ruleId, Instant slaDueAt) {
    }

    /** {@code dedupKey} identifies one logical notification so redelivered messages are not sent twice. */
    public record Notification(UUID issueId, String tenantId, UUID teamId, String kind, String dedupKey) {
    }

    private Messages() {
    }
}
