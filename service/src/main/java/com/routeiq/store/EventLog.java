package com.routeiq.store;

import com.routeiq.domain.IssueEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Append-only audit log of everything that happens to an issue. The table rejects updates and deletes. */
@Repository
public class EventLog {

    private final JdbcClient jdbc;
    private final Json json;

    public EventLog(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void append(UUID issueId, String tenantId, String eventType, Map<String, ?> payload) {
        jdbc.sql("INSERT INTO issue_events (issue_id, tenant_id, event_type, payload) VALUES (?, ?, ?, ?::jsonb)")
                .params(issueId, tenantId, eventType, json.write(payload))
                .update();
    }

    public List<IssueEvent> forIssue(UUID issueId) {
        return jdbc.sql("SELECT id, event_type, payload::text AS payload, created_at FROM issue_events WHERE issue_id = ? ORDER BY id")
                .param(issueId)
                .query((rs, n) -> new IssueEvent(
                        rs.getLong("id"),
                        rs.getString("event_type"),
                        json.tree(rs.getString("payload")),
                        IssueRepository.instant(rs, "created_at")))
                .list();
    }
}
