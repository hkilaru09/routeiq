package com.routeiq.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record IssueEvent(long id, String eventType, JsonNode payload, Instant createdAt) {
}
