package com.routeiq.store;

import com.routeiq.domain.Operator;
import com.routeiq.domain.RoutingRule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RuleRepository {

    private final JdbcClient jdbc;

    public RuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<RoutingRule> forTenant(String tenantId) {
        return jdbc.sql("SELECT * FROM routing_rules WHERE tenant_id = ? ORDER BY priority, name")
                .param(tenantId).query(this::map).list();
    }

    public Optional<RoutingRule> find(String tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM routing_rules WHERE tenant_id = ? AND id = ?")
                .params(tenantId, id).query(this::map).optional();
    }

    public void insert(RoutingRule r) {
        jdbc.sql("""
                        INSERT INTO routing_rules (id, tenant_id, name, priority, field, operator, match_value, team_id,
                                                   sla_minutes, enabled)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(r.id(), r.tenantId(), r.name(), r.priority(), r.field(), r.operator().name(), r.matchValue(),
                        r.teamId(), r.slaMinutes(), r.enabled())
                .update();
    }

    public int update(RoutingRule r) {
        return jdbc.sql("""
                        UPDATE routing_rules SET name = ?, priority = ?, field = ?, operator = ?, match_value = ?,
                               team_id = ?, sla_minutes = ?, enabled = ?, updated_at = now()
                        WHERE id = ? AND tenant_id = ?
                        """)
                .params(r.name(), r.priority(), r.field(), r.operator().name(), r.matchValue(), r.teamId(),
                        r.slaMinutes(), r.enabled(), r.id(), r.tenantId())
                .update();
    }

    public int delete(String tenantId, UUID id) {
        return jdbc.sql("DELETE FROM routing_rules WHERE id = ? AND tenant_id = ?").params(id, tenantId).update();
    }

    private RoutingRule map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new RoutingRule(
                rs.getObject("id", UUID.class),
                rs.getString("tenant_id"),
                rs.getString("name"),
                rs.getInt("priority"),
                rs.getString("field"),
                Operator.valueOf(rs.getString("operator")),
                rs.getString("match_value"),
                rs.getObject("team_id", UUID.class),
                rs.getInt("sla_minutes"),
                rs.getBoolean("enabled"));
    }
}
