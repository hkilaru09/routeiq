package com.routeiq.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestHashTest {

    @Test
    void hashIgnoresAttributeOrder() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("agency", "DOT");
        a.put("borough", "BRONX");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("borough", "BRONX");
        b.put("agency", "DOT");
        Instant t = Instant.parse("2026-10-01T12:00:00Z");
        assertThat(IngestService.hash(new IssueRequest("1", "Pothole", null, "Street Condition", a, t)))
                .isEqualTo(IngestService.hash(new IssueRequest("1", "Pothole", null, "Street Condition", b, t)));
    }

    @Test
    void hashChangesWithContent() {
        assertThat(IngestService.hash(new IssueRequest("1", "Pothole", null, "Street Condition", Map.of(), null)))
                .isNotEqualTo(IngestService.hash(new IssueRequest("1", "Pothole!", null, "Street Condition", Map.of(), null)));
    }
}
