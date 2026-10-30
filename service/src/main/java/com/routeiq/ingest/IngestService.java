package com.routeiq.ingest;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.routeiq.config.Messages;
import com.routeiq.config.Topics;
import com.routeiq.domain.Issue;
import com.routeiq.store.EventLog;
import com.routeiq.store.IssueRepository;
import com.routeiq.store.Outbox;
import com.routeiq.store.TenantRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts new issues. The idempotency key, the issue row, the CREATED audit event and the outbox message are all
 * written in one transaction, so a retried request either sees the original result or creates exactly one issue.
 */
@Service
public class IngestService {

    public record IngestResult(Issue issue, boolean created) {
    }

    /** Canonical form for request hashing: sorted keys, ISO timestamps. */
    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .build();

    private final JdbcClient jdbc;
    private final TenantRepository tenants;
    private final IssueRepository issues;
    private final EventLog events;
    private final Outbox outbox;
    private final MeterRegistry meters;

    public IngestService(JdbcClient jdbc, TenantRepository tenants, IssueRepository issues, EventLog events,
                         Outbox outbox, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.issues = issues;
        this.events = events;
        this.outbox = outbox;
        this.meters = meters;
    }

    @Transactional
    public IngestResult ingest(String tenantId, String idempotencyKey, IssueRequest request) {
        if (tenants.tenant(tenantId).isEmpty()) {
            throw new UnknownTenantException(tenantId);
        }
        String hash = hash(request);
        UUID issueId = UUID.randomUUID();

        // A concurrent request with the same key blocks here until the first commits, then inserts nothing.
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_keys (tenant_id, idem_key, request_hash, issue_id)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (tenant_id, idem_key) DO NOTHING
                        """)
                .params(tenantId, idempotencyKey, hash, issueId)
                .update();

        if (inserted == 0) {
            var existing = jdbc.sql("SELECT request_hash, issue_id FROM idempotency_keys WHERE tenant_id = ? AND idem_key = ?")
                    .params(tenantId, idempotencyKey)
                    .query((rs, n) -> Map.entry(rs.getString("request_hash"), rs.getObject("issue_id", UUID.class)))
                    .single();
            if (!existing.getKey().equals(hash)) {
                meters.counter("routeiq.ingest.idempotency", "result", "conflict").increment();
                throw new IdempotencyKeyReusedException(idempotencyKey);
            }
            meters.counter("routeiq.ingest.idempotency", "result", "replayed").increment();
            return new IngestResult(issues.find(existing.getValue()).orElseThrow(), false);
        }

        issues.insertNew(issueId, tenantId, request.externalId(), request.title(), request.description(),
                request.category(), request.attributesOrEmpty(), request.sourceCreatedAt());
        Issue issue = issues.find(issueId).orElseThrow();
        events.append(issueId, tenantId, "CREATED", Map.of("idempotencyKey", idempotencyKey));
        outbox.enqueue(Topics.ISSUES_CREATED, issueId.toString(),
                new Messages.IssueCreated(issueId, tenantId, issue.createdAt()));
        meters.counter("routeiq.issues.ingested", "tenant", tenantId).increment();
        return new IngestResult(issue, true);
    }

    static String hash(IssueRequest request) {
        try {
            byte[] canonical = CANONICAL.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static class UnknownTenantException extends RuntimeException {
        public UnknownTenantException(String tenantId) {
            super("unknown tenant: " + tenantId);
        }
    }

    public static class IdempotencyKeyReusedException extends RuntimeException {
        public IdempotencyKeyReusedException(String key) {
            super("Idempotency-Key '" + key + "' was already used with a different request body");
        }
    }
}
