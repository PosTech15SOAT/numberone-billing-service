ALTER TABLE budget_approval_authorization
    ADD COLUMN consumed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_authorization_consumption CHECK (
        consumed_at IS NULL OR (revoked_at IS NULL AND consumed_at >= created_at AND consumed_at < expires_at)
    );

DROP INDEX uk_budget_active_authorization;
CREATE UNIQUE INDEX uk_budget_active_authorization ON budget_approval_authorization (budget_id)
    WHERE revoked_at IS NULL AND consumed_at IS NULL;
