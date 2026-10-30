CREATE TABLE tenants (
    id                  TEXT PRIMARY KEY,
    name                TEXT        NOT NULL,
    default_sla_minutes INT         NOT NULL DEFAULT 240,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE teams (
    id          UUID PRIMARY KEY,
    tenant_id   TEXT        NOT NULL REFERENCES tenants (id),
    slug        TEXT        NOT NULL,
    name        TEXT        NOT NULL,
    email       TEXT,
    webhook_url TEXT,
    is_default  BOOLEAN     NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, slug)
);

-- At most one fallback team per tenant.
CREATE UNIQUE INDEX teams_one_default_per_tenant ON teams (tenant_id) WHERE is_default;

CREATE TABLE routing_rules (
    id          UUID PRIMARY KEY,
    tenant_id   TEXT        NOT NULL REFERENCES tenants (id),
    name        TEXT        NOT NULL,
    priority    INT         NOT NULL,
    field       TEXT        NOT NULL,
    operator    TEXT        NOT NULL CHECK (operator IN ('EQUALS', 'CONTAINS', 'PREFIX', 'IN', 'REGEX')),
    match_value TEXT        NOT NULL,
    team_id     UUID        NOT NULL REFERENCES teams (id),
    sla_minutes INT         NOT NULL CHECK (sla_minutes > 0),
    enabled     BOOLEAN     NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX routing_rules_tenant_priority ON routing_rules (tenant_id, priority);

CREATE TABLE issues (
    id                UUID PRIMARY KEY,
    tenant_id         TEXT        NOT NULL REFERENCES tenants (id),
    external_id       TEXT,
    title             TEXT        NOT NULL,
    description       TEXT,
    category          TEXT,
    attributes        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    status            TEXT        NOT NULL CHECK (status IN ('NEW', 'ROUTED', 'ACKNOWLEDGED', 'RESOLVED')),
    team_id           UUID REFERENCES teams (id),
    rule_id           UUID REFERENCES routing_rules (id) ON DELETE SET NULL,
    sla_due_at        TIMESTAMPTZ,
    breached_at       TIMESTAMPTZ,
    escalation_level  INT         NOT NULL DEFAULT 0,
    source_created_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    routed_at         TIMESTAMPTZ,
    resolved_at       TIMESTAMPTZ
);

CREATE INDEX issues_tenant_created ON issues (tenant_id, created_at DESC);
-- The SLA scanner only looks at open, unbreached, routed issues.
CREATE INDEX issues_sla_open ON issues (sla_due_at) WHERE status = 'ROUTED' AND breached_at IS NULL;

CREATE TABLE issue_events (
    id         BIGSERIAL PRIMARY KEY,
    issue_id   UUID        NOT NULL REFERENCES issues (id),
    tenant_id  TEXT        NOT NULL,
    event_type TEXT        NOT NULL,
    payload    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX issue_events_issue ON issue_events (issue_id, id);

-- The audit log is append-only: reject any UPDATE or DELETE at the database level.
CREATE FUNCTION issue_events_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'issue_events is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER issue_events_no_update_delete
    BEFORE UPDATE OR DELETE ON issue_events
    FOR EACH ROW EXECUTE FUNCTION issue_events_append_only();

CREATE TABLE notifications (
    id         UUID PRIMARY KEY,
    issue_id   UUID        NOT NULL REFERENCES issues (id),
    tenant_id  TEXT        NOT NULL,
    team_id    UUID REFERENCES teams (id),
    kind       TEXT        NOT NULL,
    channel    TEXT        NOT NULL CHECK (channel IN ('EMAIL', 'WEBHOOK')),
    target     TEXT        NOT NULL,
    dedup_key  TEXT        NOT NULL,
    status     TEXT        NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts   INT         NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at    TIMESTAMPTZ,
    UNIQUE (dedup_key, channel)
);

CREATE TABLE idempotency_keys (
    tenant_id    TEXT        NOT NULL REFERENCES tenants (id),
    idem_key     TEXT        NOT NULL,
    request_hash TEXT        NOT NULL,
    issue_id     UUID        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, idem_key)
);

-- Transactional outbox: rows are written in the same transaction as the state change
-- and relayed to Kafka afterwards, so a crash between commit and publish loses nothing.
CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    topic        TEXT        NOT NULL,
    msg_key      TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
