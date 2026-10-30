package com.routeiq.store;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {

    public record NotificationRow(UUID id, UUID issueId, String kind, String channel, String target, String status,
                                  int attempts, String lastError) {
    }

    private final JdbcClient jdbc;

    public NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the row for this (dedupKey, channel) if it does not exist, and returns the current row. */
    public NotificationRow claim(UUID issueId, String tenantId, UUID teamId, String kind, String channel, String target,
                                 String dedupKey) {
        jdbc.sql("""
                        INSERT INTO notifications (id, issue_id, tenant_id, team_id, kind, channel, target, dedup_key, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                        ON CONFLICT (dedup_key, channel) DO NOTHING
                        """)
                .params(UUID.randomUUID(), issueId, tenantId, teamId, kind, channel, target, dedupKey)
                .update();
        return jdbc.sql("SELECT * FROM notifications WHERE dedup_key = ? AND channel = ?")
                .params(dedupKey, channel).query(this::map).single();
    }

    public void markSent(UUID id) {
        jdbc.sql("UPDATE notifications SET status = 'SENT', attempts = attempts + 1, sent_at = now(), last_error = NULL WHERE id = ?")
                .param(id).update();
    }

    public void recordFailure(UUID id, String error) {
        jdbc.sql("UPDATE notifications SET attempts = attempts + 1, last_error = ? WHERE id = ?")
                .params(error, id).update();
    }

    /** Called once retries are exhausted. */
    public int markFailed(String dedupKey) {
        return jdbc.sql("UPDATE notifications SET status = 'FAILED' WHERE dedup_key = ? AND status = 'PENDING'")
                .param(dedupKey).update();
    }

    public List<NotificationRow> forIssue(UUID issueId) {
        return jdbc.sql("SELECT * FROM notifications WHERE issue_id = ? ORDER BY created_at, channel")
                .param(issueId).query(this::map).list();
    }

    private NotificationRow map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new NotificationRow(
                rs.getObject("id", UUID.class),
                rs.getObject("issue_id", UUID.class),
                rs.getString("kind"),
                rs.getString("channel"),
                rs.getString("target"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getString("last_error"));
    }
}
