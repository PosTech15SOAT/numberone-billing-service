ALTER TABLE budget_revision_counter
    DROP CONSTRAINT ck_budget_revision_counter_positive,
    ADD CONSTRAINT ck_budget_revision_counter_nonnegative CHECK (last_version >= 0),
    ADD COLUMN context_closed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE charge DROP CONSTRAINT ck_charge_status;
ALTER TABLE charge ADD CONSTRAINT ck_charge_status CHECK (status IN ('OPEN', 'PAID', 'REFUNDED'));

ALTER TABLE integration_event_outbox DROP CONSTRAINT ck_outbox_payment_reference;
ALTER TABLE integration_event_outbox ADD CONSTRAINT ck_outbox_payment_reference CHECK (
    (charge_id IS NULL AND event_type NOT IN ('PagamentoConfirmado', 'PagamentoAtualizado'))
    OR (charge_id IS NOT NULL AND event_type IN ('PagamentoConfirmado', 'PagamentoAtualizado')
        AND budget_id IS NULL AND budget_version IS NULL)
);

CREATE TABLE billing_cancellation_receipt (
    operation_id UUID PRIMARY KEY,
    service_order_id UUID NOT NULL,
    requested_budget_id UUID,
    requested_budget_version INTEGER,
    cancelled_budget_id UUID,
    cancelled_budget_version INTEGER,
    CHECK ((requested_budget_id IS NULL) = (requested_budget_version IS NULL)),
    CHECK ((cancelled_budget_id IS NULL) = (cancelled_budget_version IS NULL))
);

ALTER TABLE budget DROP CONSTRAINT ck_budget_approval_audit;
ALTER TABLE budget ADD CONSTRAINT ck_budget_approval_audit CHECK (
    (approval_origin IS NULL AND approved_by_employee_id IS NULL)
    OR (status IN ('APPROVED', 'CANCELLED') AND approval_origin IS NOT NULL AND approval_origin = 'IN_PERSON'
        AND approved_by_employee_id IS NOT NULL AND length(btrim(approved_by_employee_id)) > 0)
    OR (status IN ('APPROVED', 'CANCELLED') AND approval_origin IS NOT NULL AND approval_origin = 'REMOTE' AND approved_by_employee_id IS NULL)
);
