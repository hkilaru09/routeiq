package com.routeiq.api;

import com.routeiq.domain.Issue;
import com.routeiq.domain.IssueEvent;
import com.routeiq.domain.IssueStatus;
import com.routeiq.ingest.IngestService;
import com.routeiq.ingest.IssueLifecycleService;
import com.routeiq.ingest.IssueRequest;
import com.routeiq.store.IssueRepository;
import com.routeiq.store.EventLog;
import com.routeiq.store.NotificationRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/tenants/{tenantId}/issues")
public class IssueController {

    public record IssueDetail(Issue issue, List<IssueEvent> events,
                              List<NotificationRepository.NotificationRow> notifications) {
    }

    private final IngestService ingest;
    private final IssueLifecycleService lifecycle;
    private final IssueRepository issues;
    private final EventLog events;
    private final NotificationRepository notifications;

    public IssueController(IngestService ingest, IssueLifecycleService lifecycle, IssueRepository issues,
                           EventLog events, NotificationRepository notifications) {
        this.ingest = ingest;
        this.lifecycle = lifecycle;
        this.issues = issues;
        this.events = events;
        this.notifications = notifications;
    }

    /**
     * Creates an issue. Repeating a request with the same {@code Idempotency-Key} returns the original issue with
     * 200 and {@code Idempotent-Replayed: true}; reusing a key with a different body returns 422.
     */
    @PostMapping
    public ResponseEntity<Issue> create(@PathVariable String tenantId,
                                        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
                                        @Valid @RequestBody IssueRequest request) {
        IngestService.IngestResult result = ingest.ingest(tenantId, idempotencyKey, request);
        if (result.created()) {
            return ResponseEntity.created(URI.create("/api/tenants/" + tenantId + "/issues/" + result.issue().id()))
                    .body(result.issue());
        }
        return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.issue());
    }

    @GetMapping
    public List<Issue> list(@PathVariable String tenantId,
                            @RequestParam(required = false) IssueStatus status,
                            @RequestParam(required = false) Boolean breached,
                            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit) {
        return issues.list(tenantId, status, breached, limit);
    }

    @GetMapping("/stats")
    public Map<String, Long> stats(@PathVariable String tenantId) {
        return issues.countByStatus(tenantId);
    }

    @GetMapping("/{id}")
    public IssueDetail get(@PathVariable String tenantId, @PathVariable UUID id) {
        Issue issue = issues.find(tenantId, id).orElseThrow(() -> new IssueLifecycleService.IssueNotFound(id));
        return new IssueDetail(issue, events.forIssue(id), notifications.forIssue(id));
    }

    @PostMapping("/{id}/acknowledge")
    public Issue acknowledge(@PathVariable String tenantId, @PathVariable UUID id) {
        return lifecycle.acknowledge(tenantId, id);
    }

    @PostMapping("/{id}/resolve")
    public Issue resolve(@PathVariable String tenantId, @PathVariable UUID id) {
        return lifecycle.resolve(tenantId, id);
    }
}
