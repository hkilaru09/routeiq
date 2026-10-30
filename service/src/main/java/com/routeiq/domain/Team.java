package com.routeiq.domain;

import java.util.UUID;

public record Team(UUID id, String tenantId, String slug, String name, String email, String webhookUrl, boolean isDefault) {
}
