CREATE TABLE budget_approval_authorization (
    authorization_id UUID PRIMARY KEY,
    budget_id UUID NOT NULL REFERENCES budget(budget_id),
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_authorization_expiry CHECK (expires_at > created_at),
    CONSTRAINT uk_authorization_budget UNIQUE (authorization_id, budget_id)
);

-- Expiração é verificada no uso; não exige atualização nem job para liberar a revisão.
CREATE UNIQUE INDEX uk_budget_active_authorization ON budget_approval_authorization (budget_id)
    WHERE revoked_at IS NULL;

CREATE TABLE budget_email_notification (
    notification_id UUID PRIMARY KEY,
    authorization_id UUID NOT NULL UNIQUE,
    budget_id UUID NOT NULL REFERENCES budget(budget_id),
    protected_content BYTEA,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(60),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    CONSTRAINT fk_notification_authorization_budget FOREIGN KEY (authorization_id, budget_id)
        REFERENCES budget_approval_authorization (authorization_id, budget_id),
    CONSTRAINT ck_notification_status CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_notification_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_notification_content CHECK (status <> 'PENDING' OR protected_content IS NOT NULL),
    CONSTRAINT ck_notification_lease CHECK ((lease_token IS NULL) = (lease_until IS NULL))
);

CREATE INDEX idx_budget_notification_latest ON budget_email_notification (budget_id, created_at DESC, notification_id DESC);
CREATE INDEX idx_budget_notification_pending ON budget_email_notification (next_attempt_at, created_at, notification_id)
    WHERE status = 'PENDING';
