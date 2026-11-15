ALTER TABLE payment_attempt
    ALTER COLUMN payment_method DROP NOT NULL,
    ADD COLUMN provider_order_id VARCHAR(255),
    ADD COLUMN checkout_url VARCHAR(2048),
    ADD CONSTRAINT uk_payment_attempt_provider_order UNIQUE (provider_order_id),
    ADD CONSTRAINT ck_payment_attempt_provider_order CHECK (provider_order_id IS NULL
        OR (length(btrim(provider_order_id)) > 0 AND provider_order_id = btrim(provider_order_id)
            AND provider_order_id !~ '[[:cntrl:]]')),
    ADD CONSTRAINT ck_payment_attempt_checkout_url CHECK (checkout_url IS NULL
        OR (provider_order_id IS NOT NULL
            AND checkout_url ~* '^https://[^/?#[:space:]@]+([/?][^#[:space:]]*)?$'
            AND checkout_url !~ '[[:cntrl:]]'));

-- Os checks de estado, meio, quitação, datas e o índice de tentativa bloqueante da V9 permanecem ativos.
-- Valores históricos de Charge não são arredondados nem reclassificados pela política monetária nova.
