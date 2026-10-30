package com.routeiq.ingest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

public record IssueRequest(
        @Size(max = 200) String externalId,
        @NotBlank @Size(max = 500) String title,
        @Size(max = 10_000) String description,
        @Size(max = 200) String category,
        Map<@Size(max = 100) String, @Size(max = 1000) String> attributes,
        Instant sourceCreatedAt) {

    public Map<String, String> attributesOrEmpty() {
        return attributes == null ? Map.of() : attributes;
    }
}
