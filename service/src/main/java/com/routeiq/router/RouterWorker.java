package com.routeiq.router;

import com.routeiq.config.Messages;
import com.routeiq.config.Topics;
import com.routeiq.store.Json;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code issues.created}. Failures go to {@code issues.created-retry-<delayMs>} with exponential backoff and then
 * to {@code issues.created-dlt}. Malformed messages and unknown issues skip the retries.
 */
@Component
@ConditionalOnProperty(name = "routeiq.router.enabled", havingValue = "true", matchIfMissing = true)
public class RouterWorker {

    private static final Logger log = LoggerFactory.getLogger(RouterWorker.class);

    private final RoutingService routing;
    private final Json json;
    private final MeterRegistry meters;

    public RouterWorker(RoutingService routing, Json json, MeterRegistry meters) {
        this.routing = routing;
        this.json = json;
        this.meters = meters;
    }

    @RetryableTopic(
            attempts = "${routeiq.kafka.retry-attempts:4}",
            backoff = @Backoff(delayExpression = "${routeiq.kafka.retry-delay-ms:500}", multiplier = 2.0),
            exclude = {Json.MalformedMessageException.class, RoutingService.IssueNotFoundException.class})
    @KafkaListener(topics = Topics.ISSUES_CREATED, groupId = "routeiq-router",
            concurrency = "${routeiq.router.concurrency:3}")
    public void onIssueCreated(String payload) {
        Messages.IssueCreated message = json.read(payload, Messages.IssueCreated.class);
        routing.route(message.issueId());
    }

    @DltHandler
    public void onDeadLetter(String payload, @Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String error) {
        meters.counter("routeiq.dlt.messages", "topic", Topics.ISSUES_CREATED).increment();
        log.error("issues.created message dead-lettered: {} payload={}", error, payload);
    }
}
