package com.routeiq.domain;

import java.util.UUID;

public record RoutingRule(
        UUID id,
        String tenantId,
        String name,
        int priority,
        String field,
        Operator operator,
        String matchValue,
        UUID teamId,
        int slaMinutes,
        boolean enabled) {
}
