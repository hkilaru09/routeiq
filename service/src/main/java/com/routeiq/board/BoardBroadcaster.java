package com.routeiq.board;

import com.fasterxml.jackson.databind.JsonNode;
import com.routeiq.config.Topics;
import com.routeiq.store.IssueRepository;
import com.routeiq.store.Json;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Pushes issue changes to connected dashboards. Every API instance uses its own consumer group so each one sees all
 * events, regardless of which instance or worker produced them.
 */
@Component
@ConditionalOnProperty(name = "routeiq.board.enabled", havingValue = "true", matchIfMissing = true)
public class BoardBroadcaster {

    private final BoardHub hub;
    private final IssueRepository issues;
    private final Json json;

    public BoardBroadcaster(BoardHub hub, IssueRepository issues, Json json) {
        this.hub = hub;
        this.issues = issues;
        this.json = json;
    }

    @KafkaListener(topics = {Topics.ISSUES_CREATED, Topics.ISSUES_ROUTED, Topics.NOTIFICATIONS},
            groupId = "routeiq-board-#{T(java.util.UUID).randomUUID()}",
            properties = "auto.offset.reset=latest")
    public void onEvent(ConsumerRecord<String, String> record) {
        if (hub.subscriberCount() == 0) {
            return;
        }
        JsonNode message = json.tree(record.value());
        String type = switch (record.topic()) {
            case Topics.ISSUES_CREATED -> "CREATED";
            case Topics.ISSUES_ROUTED -> "ROUTED";
            default -> message.path("kind").asText();
        };
        if ("ROUTED".equals(type) && Topics.NOTIFICATIONS.equals(record.topic())) {
            return; // already broadcast from issues.routed
        }
        UUID issueId = UUID.fromString(message.path("issueId").asText());
        issues.find(issueId).ifPresent(issue -> {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", type);
            event.put("issue", issue);
            hub.broadcast(issue.tenantId(), json.write(event));
        });
    }
}
