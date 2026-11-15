CREATE TABLE integration_event_outbox (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    schema_version INTEGER NOT NULL,
    service_order_id UUID NOT NULL,
    budget_id UUID,
    budget_version INTEGER,
    envelope JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error TEXT,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    CONSTRAINT uk_outbox_budget_event UNIQUE (budget_id, event_type),
    CONSTRAINT ck_outbox_schema_version_positive CHECK (schema_version > 0),
    CONSTRAINT ck_outbox_budget_reference CHECK (
        (budget_id IS NULL AND budget_version IS NULL)
        OR (budget_id IS NOT NULL AND budget_version IS NOT NULL AND budget_version > 0)
    ),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'NEEDS_REVIEW')),
    CONSTRAINT ck_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbox_envelope_object CHECK (jsonb_typeof(envelope) = 'object')
);

CREATE INDEX idx_outbox_pending ON integration_event_outbox (next_attempt_at, created_at, event_id)
    WHERE status = 'PENDING';
