package br.com.fiap.numberone.billing.payment.application.exceptions;

import java.util.UUID;

/** A reserva continua durável mesmo quando a atualização local do resultado falha. */
public class CheckoutOutcomeUnknownException extends RuntimeException {
    private final UUID chargeId;
    private final UUID attemptId;

    public CheckoutOutcomeUnknownException(UUID chargeId, UUID attemptId, Throwable cause) {
        super("Resultado do checkout exige reconciliação; não repetir a criação externa", cause);
        this.chargeId = chargeId;
        this.attemptId = attemptId;
    }

    public UUID getChargeId() { return chargeId; }
    public UUID getAttemptId() { return attemptId; }
}
