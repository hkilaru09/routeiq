package com.routeiq.notify;

import com.routeiq.config.Messages;
import com.routeiq.config.Topics;
import com.routeiq.domain.Issue;
import com.routeiq.domain.Team;
import com.routeiq.router.RoutingService;
import com.routeiq.store.EventLog;
import com.routeiq.store.IssueRepository;
import com.routeiq.store.Json;
import com.routeiq.store.NotificationRepository;
import com.routeiq.store.TenantRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Delivers notifications over every channel the team has configured. Each (dedupKey, channel) pair is tracked in the
 * notifications table, so a redelivered message only retries the channels that have not succeeded yet.
 */
@Component
@ConditionalOnProperty(name = "routeiq.notifier.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);

    private final List<NotificationSender> senders;
    private final NotificationRepository notifications;
    private final IssueRepository issues;
    private final TenantRepository tenants;
    private final EventLog events;
    private final Json json;
    private final MeterRegistry meters;

    public NotificationWorker(List<NotificationSender> senders, NotificationRepository notifications,
                              IssueRepository issues, TenantRepository tenants, EventLog events, Json json,
                              MeterRegistry meters) {
        this.senders = senders;
        this.notifications = notifications;
        this.issues = issues;
        this.tenants = tenants;
        this.events = events;
        this.json = json;
        this.meters = meters;
    }

    @RetryableTopic(
            attempts = "${routeiq.kafka.retry-attempts:4}",
            backoff = @Backoff(delayExpression = "${routeiq.kafka.retry-delay-ms:500}", multiplier = 2.0),
            exclude = {Json.MalformedMessageException.class, RoutingService.IssueNotFoundException.class})
    @KafkaListener(topics = Topics.NOTIFICATIONS, groupId = "routeiq-notifier",
            concurrency = "${routeiq.notifier.concurrency:3}")
    public void onNotification(String payload) {
        Messages.Notification message = json.read(payload, Messages.Notification.class);
        Issue issue = issues.find(message.issueId())
                .orElseThrow(() -> new RoutingService.IssueNotFoundException(message.issueId()));
        Team team = tenants.team(message.teamId()).orElseThrow();

        List<String> failures = new ArrayList<>();
        for (NotificationSender sender : senders) {
            String target = sender.target(team);
            if (target == null || target.isBlank()) {
                continue;
            }
            var row = notifications.claim(issue.id(), issue.tenantId(), team.id(), message.kind(), sender.channel(),
                    target, message.dedupKey());
            if ("SENT".equals(row.status())) {
                meters.counter("routeiq.notifications", "channel", sender.channel(), "result", "duplicate").increment();
                continue;
            }
            try {
                sender.send(target, message.kind(), issue, team);
                notifications.markSent(row.id());
                meters.counter("routeiq.notifications", "channel", sender.channel(), "result", "sent").increment();
            } catch (RuntimeException e) {
                notifications.recordFailure(row.id(), e.toString());
                meters.counter("routeiq.notifications", "channel", sender.channel(), "result", "error").increment();
                failures.add(sender.channel() + ": " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new DeliveryFailedException(String.join("; ", failures));
        }
    }

    @DltHandler
    public void onDeadLetter(String payload, @Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String error) {
        meters.counter("routeiq.dlt.messages", "topic", Topics.NOTIFICATIONS).increment();
        log.error("notification dead-lettered: {} payload={}", error, payload);
        try {
            Messages.Notification message = json.read(payload, Messages.Notification.class);
            notifications.markFailed(message.dedupKey());
            if (issues.find(message.issueId()).isPresent()) {
                events.append(message.issueId(), message.tenantId(), "NOTIFICATION_FAILED",
                        Map.of("kind", message.kind(), "error", String.valueOf(error)));
            }
        } catch (Json.MalformedMessageException ignored) {
            // Nothing to record for a payload we cannot read.
        }
    }

    public static class DeliveryFailedException extends RuntimeException {
        public DeliveryFailedException(String message) {
            super(message);
        }
    }
}
