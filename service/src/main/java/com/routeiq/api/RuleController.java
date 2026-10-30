package com.routeiq.api;

import com.routeiq.domain.Operator;
import com.routeiq.domain.RoutingRule;
import com.routeiq.domain.Team;
import com.routeiq.domain.Tenant;
import com.routeiq.ingest.IssueRequest;
import com.routeiq.rules.RuleCache;
import com.routeiq.rules.RuleEngine;
import com.routeiq.store.RuleRepository;
import com.routeiq.store.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenants")
public class RuleController {

    public record RuleRequest(
            @NotBlank String name,
            @NotNull @Min(0) Integer priority,
            @NotBlank String field,
            @NotBlank String operator,
            @NotBlank String matchValue,
            @NotNull UUID teamId,
            @NotNull @Min(1) Integer slaMinutes,
            Boolean enabled) {
    }

    public record DryRunResult(RoutingRule rule, Team team, int slaMinutes) {
    }

    private final TenantRepository tenants;
    private final RuleRepository rules;
    private final RuleCache cache;
    private final RuleEngine engine;

    public RuleController(TenantRepository tenants, RuleRepository rules, RuleCache cache, RuleEngine engine) {
        this.tenants = tenants;
        this.rules = rules;
        this.cache = cache;
        this.engine = engine;
    }

    @GetMapping
    public List<Tenant> tenants() {
        return tenants.tenants();
    }

    @GetMapping("/{tenantId}/teams")
    public List<Team> teams(@PathVariable String tenantId) {
        return tenants.teams(tenantId);
    }

    @GetMapping("/{tenantId}/rules")
    public List<RoutingRule> list(@PathVariable String tenantId) {
        return rules.forTenant(tenantId);
    }

    @PostMapping("/{tenantId}/rules")
    @Transactional
    public ResponseEntity<RoutingRule> create(@PathVariable String tenantId, @Valid @RequestBody RuleRequest request) {
        RoutingRule rule = toRule(tenantId, UUID.randomUUID(), request);
        rules.insert(rule);
        cache.evict(tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(rule);
    }

    @PutMapping("/{tenantId}/rules/{id}")
    @Transactional
    public RoutingRule update(@PathVariable String tenantId, @PathVariable UUID id, @Valid @RequestBody RuleRequest request) {
        RoutingRule rule = toRule(tenantId, id, request);
        if (rules.update(rule) == 0) {
            throw new NotFoundException("rule not found: " + id);
        }
        cache.evict(tenantId);
        return rule;
    }

    @DeleteMapping("/{tenantId}/rules/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable String tenantId, @PathVariable UUID id) {
        if (rules.delete(tenantId, id) == 0) {
            throw new NotFoundException("rule not found: " + id);
        }
        cache.evict(tenantId);
        return ResponseEntity.noContent().build();
    }

    /** Shows which rule and team a hypothetical issue would be routed to, without creating it. */
    @PostMapping("/{tenantId}/rules/dry-run")
    public DryRunResult dryRun(@PathVariable String tenantId, @Valid @RequestBody IssueRequest request) {
        Tenant tenant = tenants.tenant(tenantId).orElseThrow(() -> new NotFoundException("unknown tenant: " + tenantId));
        var probe = new com.routeiq.domain.Routable() {
            public String externalId() { return request.externalId(); }
            public String title() { return request.title(); }
            public String description() { return request.description(); }
            public String category() { return request.category(); }
            public Map<String, String> attributes() { return request.attributesOrEmpty(); }
        };
        var match = engine.match(rules.forTenant(tenantId), probe);
        if (match.isPresent()) {
            return new DryRunResult(match.get(), tenants.team(match.get().teamId()).orElse(null), match.get().slaMinutes());
        }
        return new DryRunResult(null, tenants.defaultTeam(tenantId).orElse(null), tenant.defaultSlaMinutes());
    }

    private RoutingRule toRule(String tenantId, UUID id, RuleRequest r) {
        RuleEngine.validate(r.field(), r.operator(), r.matchValue()).ifPresent(error -> {
            throw new BadRequestException(error);
        });
        Team team = tenants.team(r.teamId())
                .filter(t -> t.tenantId().equals(tenantId))
                .orElseThrow(() -> new BadRequestException("team " + r.teamId() + " does not belong to tenant " + tenantId));
        return new RoutingRule(id, tenantId, r.name(), r.priority(), r.field(), Operator.valueOf(r.operator()),
                r.matchValue(), team.id(), r.slaMinutes(), r.enabled() == null || r.enabled());
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }
}
