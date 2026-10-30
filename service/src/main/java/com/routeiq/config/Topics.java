package com.routeiq.config;

/** Kafka topic names. Retry ({@code -retry-N}) and dead-letter ({@code -dlt}) topics are derived by Spring Kafka. */
public final class Topics {

    public static final String ISSUES_CREATED = "issues.created";
    public static final String ISSUES_ROUTED = "issues.routed";
    public static final String NOTIFICATIONS = "notifications";

    private Topics() {
    }
}
