package com.routeiq.sla;

import com.routeiq.config.Messages;
import com.routeiq.config.Topics;
import com.routeiq.domain.Issue;
import com.routeiq.store.EventLog;
import com.routeiq.store.IssueRepository;
import com.routeiq.store.Outbox;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finds routed issues whose SLA has passed, marks them breached and queues an ESCALATION notification. Rows are
 * claimed with SKIP LOCKED, so running more than one scheduler is safe.
 */
@Component
@ConditionalOnProperty(name = "routeiq.sla.enabled", havingValue = "true", matchIfMissing = true)
public class SlaScheduler {

    private final IssueRepository issues;
    private final EventLog events;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Clock clock;
    private final int batchSize;

    public SlaScheduler(IssueRepository issues, EventLog events, Outbox outbox, TransactionTemplate tx,
                        MeterRegistry meters, Clock clock, @Value("${routeiq.sla.batch-size:500}") int batchSize) {
        this.issues = issues;
        this.events = events;
        this.outbox = outbox;
        this.tx = tx;
        this.meters = meters;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${routeiq.sla.scan-interval-ms:15000}", initialDelayString = "${routeiq.sla.initial-delay-ms:5000}")
    public void scan() {
        Integer escalated;
        do {
            escalated = tx.execute(status -> escalateBatch());
        } while (escalated != null && escalated == batchSize);
    }

    int escalateBatch() {
        Instant now = clock.instant();
        List<Issue> breached = issues.lockBreached(now, batchSize);
        for (Issue issue : breached) {
            issues.markBreached(issue.id(), now);
            int level = issue.escalationLevel() + 1;
            events.append(issue.id(), issue.tenantId(), "SLA_BREACHED",
                    Map.of("slaDueAt", issue.slaDueAt(), "detectedAt", now, "escalationLevel", level));
            outbox.enqueue(Topics.NOTIFICATIONS, issue.id().toString(), new Messages.Notification(
                    issue.id(), issue.tenantId(), issue.teamId(), "ESCALATION", issue.id() + ":ESCALATION:" + level));
            meters.counter("routeiq.sla.breaches", "tenant", issue.tenantId()).increment();
            meters.timer("routeiq.sla.detection.delay").record(Duration.between(issue.slaDueAt(), now));
        }
        return breached.size();
    }
}
