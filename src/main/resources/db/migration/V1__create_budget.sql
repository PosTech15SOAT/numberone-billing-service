CREATE TABLE budget (
    budget_id UUID PRIMARY KEY,
    service_order_id UUID NOT NULL,
    budget_version INTEGER NOT NULL,
    quoted_amount NUMERIC NOT NULL,
    approved_amount NUMERIC,
    status VARCHAR(20) NOT NULL,
    sent_at TIMESTAMP,
    approved_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT ck_budget_version_positive CHECK (budget_version > 0),
    CONSTRAINT uk_budget_service_order_version UNIQUE (service_order_id, budget_version),
    CONSTRAINT ck_budget_status CHECK (status IN ('DRAFT', 'SENT', 'APPROVED', 'REJECTED', 'CANCELLED'))
);
