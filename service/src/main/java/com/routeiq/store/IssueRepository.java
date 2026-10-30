package com.routeiq.store;

import com.routeiq.domain.Issue;
import com.routeiq.domain.IssueStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IssueRepository {

    private static final String SELECT = """
            SELECT i.*, t.slug AS team_slug
            FROM issues i LEFT JOIN teams t ON t.id = i.team_id
            """;

    private final JdbcClient jdbc;
    private final Json json;

    public IssueRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insertNew(UUID id, String tenantId, String externalId, String title, String description,
                          String category, Map<String, String> attributes, Instant sourceCreatedAt) {
        jdbc.sql("""
                        INSERT INTO issues (id, tenant_id, external_id, title, description, category, attributes,
                                            status, source_created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'NEW', ?)
                        """)
                .params(id, tenantId, externalId, title, description, category, json.write(attributes),
                        ts(sourceCreatedAt))
                .update();
    }

    public Optional<Issue> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE i.id = ?").param(id).query(this::map).optional();
    }

    public Optional<Issue> find(String tenantId, UUID id) {
        return jdbc.sql(SELECT + " WHERE i.id = ? AND i.tenant_id = ?").params(id, tenantId).query(this::map).optional();
    }

    /** Locks the row so concurrent deliveries of the same message serialize. */
    public Optional<Issue> findForUpdate(UUID id) {
        return jdbc.sql(SELECT + " WHERE i.id = ? FOR UPDATE OF i").param(id).query(this::map).optional();
    }

    public List<Issue> list(String tenantId, IssueStatus status, Boolean breached, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE i.tenant_id = :tenant");
        if (status != null) {
            sql.append(" AND i.status = :status");
        }
        if (breached != null) {
            sql.append(breached ? " AND i.breached_at IS NOT NULL" : " AND i.breached_at IS NULL");
        }
        sql.append(" ORDER BY i.created_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("tenant", tenantId).param("limit", limit);
        if (status != null) {
            spec = spec.param("status", status.name());
        }
        return spec.query(this::map).list();
    }

    public Map<String, Long> countByStatus(String tenantId) {
        return jdbc.sql("""
                        SELECT status, count(*) AS n FROM issues WHERE tenant_id = ? GROUP BY status
                        UNION ALL
                        SELECT 'BREACHED', count(*) FROM issues WHERE tenant_id = ? AND breached_at IS NOT NULL
                        """)
                .params(tenantId, tenantId)
                .query((rs, n) -> Map.entry(rs.getString("status"), rs.getLong("n")))
                .list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public int markRouted(UUID id, UUID teamId, UUID ruleId, Instant slaDueAt) {
        return jdbc.sql("""
                        UPDATE issues SET status = 'ROUTED', team_id = ?, rule_id = ?, sla_due_at = ?, routed_at = now()
                        WHERE id = ? AND status = 'NEW'
                        """)
                .params(teamId, ruleId, ts(slaDueAt), id)
                .update();
    }

    public int transition(UUID id, String tenantId, IssueStatus from, IssueStatus to) {
        String resolved = to == IssueStatus.RESOLVED ? ", resolved_at = now()" : "";
        return jdbc.sql("UPDATE issues SET status = ?" + resolved + " WHERE id = ? AND tenant_id = ? AND status = ?")
                .params(to.name(), id, tenantId, from.name())
                .update();
    }

    /** Routed issues past their SLA that have not been escalated yet, locked for this scanner. */
    public List<Issue> lockBreached(Instant now, int limit) {
        return jdbc.sql(SELECT + """
                         WHERE i.status = 'ROUTED' AND i.breached_at IS NULL AND i.sla_due_at < ?
                        ORDER BY i.sla_due_at
                        LIMIT ?
                        FOR UPDATE OF i SKIP LOCKED
                        """)
                .params(ts(now), limit)
                .query(this::map)
                .list();
    }

    public void markBreached(UUID id, Instant now) {
        jdbc.sql("UPDATE issues SET breached_at = ?, escalation_level = escalation_level + 1 WHERE id = ?")
                .params(ts(now), id)
                .update();
    }

    private Issue map(ResultSet rs, int row) throws SQLException {
        return new Issue(
                rs.getObject("id", UUID.class),
                rs.getString("tenant_id"),
                rs.getString("external_id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("category"),
                json.readStringMap(rs.getString("attributes")),
                IssueStatus.valueOf(rs.getString("status")),
                rs.getObject("team_id", UUID.class),
                rs.getString("team_slug"),
                rs.getObject("rule_id", UUID.class),
                instant(rs, "sla_due_at"),
                instant(rs, "breached_at"),
                rs.getInt("escalation_level"),
                instant(rs, "source_created_at"),
                instant(rs, "created_at"),
                instant(rs, "routed_at"),
                instant(rs, "resolved_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
