ALTER TABLE payment_attempt
    ADD COLUMN checkout_creation_claimed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_payment_attempt_checkout_claim_time CHECK (
        checkout_creation_claimed_at IS NULL OR (
            checkout_creation_claimed_at >= created_at AND checkout_creation_claimed_at <= updated_at
        )
    );

-- Reservas não expiram. Registros anteriores permanecem intactos, sem autorização de novo POST.
