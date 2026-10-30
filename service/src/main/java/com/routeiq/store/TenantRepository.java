package com.routeiq.store;

import com.routeiq.domain.Team;
import com.routeiq.domain.Tenant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TenantRepository {

    private final JdbcClient jdbc;

    public TenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Tenant> tenants() {
        return jdbc.sql("SELECT id, name, default_sla_minutes FROM tenants ORDER BY id")
                .query((rs, n) -> new Tenant(rs.getString("id"), rs.getString("name"), rs.getInt("default_sla_minutes")))
                .list();
    }

    public Optional<Tenant> tenant(String id) {
        return jdbc.sql("SELECT id, name, default_sla_minutes FROM tenants WHERE id = ?")
                .param(id)
                .query((rs, n) -> new Tenant(rs.getString("id"), rs.getString("name"), rs.getInt("default_sla_minutes")))
                .optional();
    }

    public List<Team> teams(String tenantId) {
        return jdbc.sql("SELECT * FROM teams WHERE tenant_id = ? ORDER BY slug").param(tenantId).query(this::mapTeam).list();
    }

    public Optional<Team> team(UUID id) {
        return jdbc.sql("SELECT * FROM teams WHERE id = ?").param(id).query(this::mapTeam).optional();
    }

    public Optional<Team> defaultTeam(String tenantId) {
        return jdbc.sql("SELECT * FROM teams WHERE tenant_id = ? AND is_default").param(tenantId).query(this::mapTeam).optional();
    }

    private Team mapTeam(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Team(
                rs.getObject("id", UUID.class),
                rs.getString("tenant_id"),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("email"),
                rs.getString("webhook_url"),
                rs.getBoolean("is_default"));
    }
}
