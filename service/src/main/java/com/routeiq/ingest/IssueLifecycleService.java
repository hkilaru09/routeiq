package com.routeiq.ingest;

import com.routeiq.config.Messages;
import com.routeiq.config.Topics;
import com.routeiq.domain.Issue;
import com.routeiq.domain.IssueStatus;
import com.routeiq.store.EventLog;
import com.routeiq.store.IssueRepository;
import com.routeiq.store.Outbox;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Acknowledge and resolve. Acknowledging stops the SLA clock; both notify the team and update the live board. */
@Service
public class IssueLifecycleService {

    private final IssueRepository issues;
    private final EventLog events;
    private final Outbox outbox;

    public IssueLifecycleService(IssueRepository issues, EventLog events, Outbox outbox) {
        this.issues = issues;
        this.events = events;
        this.outbox = outbox;
    }

    @Transactional
    public Issue acknowledge(String tenantId, UUID id) {
        return transition(tenantId, id, Set.of(IssueStatus.ROUTED), IssueStatus.ACKNOWLEDGED);
    }

    @Transactional
    public Issue resolve(String tenantId, UUID id) {
        return transition(tenantId, id, Set.of(IssueStatus.ROUTED, IssueStatus.ACKNOWLEDGED), IssueStatus.RESOLVED);
    }

    private Issue transition(String tenantId, UUID id, Set<IssueStatus> allowedFrom, IssueStatus to) {
        Issue issue = issues.find(tenantId, id).orElseThrow(() -> new IssueNotFound(id));
        if (!allowedFrom.contains(issue.status()) || issues.transition(id, tenantId, issue.status(), to) == 0) {
            throw new InvalidTransitionException(issue.status(), to);
        }
        events.append(id, tenantId, to.name(), Map.of("from", issue.status().name()));
        outbox.enqueue(Topics.NOTIFICATIONS, id.toString(),
                new Messages.Notification(id, tenantId, issue.teamId(), to.name(), id + ":" + to.name()));
        return issues.find(id).orElseThrow();
    }

    public static class IssueNotFound extends RuntimeException {
        public IssueNotFound(UUID id) {
            super("issue not found: " + id);
        }
    }

    public static class InvalidTransitionException extends RuntimeException {
        public InvalidTransitionException(IssueStatus from, IssueStatus to) {
            super("cannot move issue from " + from + " to " + to);
        }
    }
}
