package com.routeiq.router;

import com.routeiq.config.Messages;
import com.routeiq.config.Topics;
import com.routeiq.domain.Issue;
import com.routeiq.domain.IssueStatus;
import com.routeiq.domain.RoutingRule;
import com.routeiq.domain.Team;
import com.routeiq.domain.Tenant;
import com.routeiq.rules.RuleCache;
import com.routeiq.rules.RuleEngine;
import com.routeiq.store.EventLog;
import com.routeiq.store.IssueRepository;
import com.routeiq.store.Outbox;
import com.routeiq.store.TenantRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoutingService {

    public enum Outcome { ROUTED, ALREADY_ROUTED }

    private final IssueRepository issues;
    private final TenantRepository tenants;
    private final RuleCache rules;
    private final RuleEngine engine;
    private final EventLog events;
    private final Outbox outbox;
    private final MeterRegistry meters;

    public RoutingService(IssueRepository issues, TenantRepository tenants, RuleCache rules, RuleEngine engine,
                          EventLog events, Outbox outbox, MeterRegistry meters) {
        this.issues = issues;
        this.tenants = tenants;
        this.rules = rules;
        this.engine = engine;
        this.events = events;
        this.outbox = outbox;
        this.meters = meters;
    }

    @Transactional
    public Outcome route(UUID issueId) {
        Issue issue = issues.findForUpdate(issueId).orElseThrow(() -> new IssueNotFoundException(issueId));
        if (issue.status() != IssueStatus.NEW) {
            // Redelivery after a crash or rebalance: the work is already done.
            meters.counter("routeiq.router.duplicates").increment();
            return Outcome.ALREADY_ROUTED;
        }

        Optional<RoutingRule> match = engine.match(rules.rulesFor(issue.tenantId()), issue);
        UUID teamId;
        int slaMinutes;
        if (match.isPresent()) {
            teamId = match.get().teamId();
            slaMinutes = match.get().slaMinutes();
        } else {
            Tenant tenant = tenants.tenant(issue.tenantId()).orElseThrow();
            teamId = tenants.defaultTeam(issue.tenantId()).map(Team::id)
                    .orElseThrow(() -> new IllegalStateException("tenant " + issue.tenantId() + " has no default team"));
            slaMinutes = tenant.defaultSlaMinutes();
        }
        UUID ruleId = match.map(RoutingRule::id).orElse(null);
        Instant slaDueAt = issue.createdAt().plus(Duration.ofMinutes(slaMinutes));

        issues.markRouted(issueId, teamId, ruleId, slaDueAt);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("teamId", teamId);
        payload.put("ruleId", ruleId);
        payload.put("rule", match.map(RoutingRule::name).orElse("default"));
        payload.put("slaDueAt", slaDueAt);
        events.append(issueId, issue.tenantId(), "ROUTED", payload);

        String key = issueId.toString();
        outbox.enqueue(Topics.ISSUES_ROUTED, key,
                new Messages.IssueRouted(issueId, issue.tenantId(), teamId, ruleId, slaDueAt));
        outbox.enqueue(Topics.NOTIFICATIONS, key,
                new Messages.Notification(issueId, issue.tenantId(), teamId, "ROUTED", issueId + ":ROUTED"));

        meters.counter("routeiq.issues.routed", "tenant", issue.tenantId(), "matched", String.valueOf(match.isPresent()))
                .increment();
        meters.timer("routeiq.route.latency", "tenant", issue.tenantId())
                .record(Duration.between(issue.createdAt(), Instant.now()));
        return Outcome.ROUTED;
    }

    /** The message refers to an issue that does not exist; retrying cannot fix it. */
    public static class IssueNotFoundException extends RuntimeException {
        public IssueNotFoundException(UUID id) {
            super("issue not found: " + id);
        }
    }
}
