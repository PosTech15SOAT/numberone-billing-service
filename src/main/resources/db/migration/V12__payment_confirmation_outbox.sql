-- Reutiliza a Outbox e seu publisher, sem alterar eventos existentes ou gerar backfill financeiro.
ALTER TABLE integration_event_outbox
    ADD COLUMN charge_id UUID,
    ADD CONSTRAINT fk_outbox_charge FOREIGN KEY (charge_id) REFERENCES charge (charge_id),
    ADD CONSTRAINT ck_outbox_payment_reference CHECK (
        (charge_id IS NULL AND event_type <> 'PagamentoConfirmado')
        OR (charge_id IS NOT NULL AND event_type = 'PagamentoConfirmado'
            AND budget_id IS NULL AND budget_version IS NULL)
    );

CREATE UNIQUE INDEX uk_outbox_charge_confirmation ON integration_event_outbox (charge_id, event_type)
    WHERE charge_id IS NOT NULL;
