CREATE TABLE charge (
    charge_id UUID PRIMARY KEY,
    service_order_id UUID NOT NULL,
    budget_id UUID NOT NULL,
    amount NUMERIC NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_charge_service_order UNIQUE (service_order_id),
    CONSTRAINT fk_charge_budget FOREIGN KEY (budget_id) REFERENCES budget (budget_id),
    CONSTRAINT ck_charge_amount CHECK (amount > 0 AND amount < 'Infinity'::numeric),
    CONSTRAINT ck_charge_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_charge_status CHECK (status IN ('OPEN', 'PAID')),
    CONSTRAINT ck_charge_dates CHECK (updated_at >= created_at),
    CONSTRAINT ck_charge_row_version CHECK (row_version >= 0)
);

-- A FK local permite verificar/restringir a remoção de um orçamento referenciado.
CREATE INDEX ix_charge_budget ON charge (budget_id);

CREATE TABLE payment_attempt (
    attempt_id UUID PRIMARY KEY,
    charge_id UUID NOT NULL,
    payment_method VARCHAR(10) NOT NULL,
    status VARCHAR(10) NOT NULL,
    idempotency_key UUID NOT NULL,
    provider_payment_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_payment_attempt_charge FOREIGN KEY (charge_id) REFERENCES charge (charge_id),
    CONSTRAINT uk_payment_attempt_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uk_payment_attempt_provider_payment UNIQUE (provider_payment_id),
    CONSTRAINT ck_payment_attempt_method CHECK (payment_method IN ('PIX', 'CARD')),
    CONSTRAINT ck_payment_attempt_status CHECK (status IN ('PENDING', 'UNKNOWN', 'PAID', 'FAILED', 'EXPIRED')),
    CONSTRAINT ck_payment_attempt_provider_payment CHECK (provider_payment_id IS NULL
        OR (length(btrim(provider_payment_id)) > 0 AND provider_payment_id = btrim(provider_payment_id)
            AND provider_payment_id !~ '[[:cntrl:]]')),
    CONSTRAINT ck_payment_attempt_paid CHECK (status <> 'PAID' OR provider_payment_id IS NOT NULL),
    CONSTRAINT ck_payment_attempt_dates CHECK (updated_at >= created_at)
);

-- Uma operação em aberto, desconhecida ou quitada impede outra operação válida na mesma cobrança.
CREATE UNIQUE INDEX uk_payment_attempt_blocking_charge ON payment_attempt (charge_id)
    WHERE status IN ('PENDING', 'UNKNOWN', 'PAID');

CREATE INDEX ix_payment_attempt_charge_history ON payment_attempt (charge_id, created_at, attempt_id);
