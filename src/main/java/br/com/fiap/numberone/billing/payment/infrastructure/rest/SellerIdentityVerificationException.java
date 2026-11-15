package br.com.fiap.numberone.billing.payment.infrastructure.rest;

import br.com.fiap.numberone.billing.payment.application.exceptions.CheckoutCreationException;

/** Falha anterior à operação de Orders; não comprova ausência de uma criação remota prévia. */
public class SellerIdentityVerificationException extends CheckoutCreationException {
    public enum Reason { DISABLED, ACTIVE_TRANSACTION, AUTHORIZATION, IDENTITY_MISMATCH, INVALID_RESPONSE, TIMEOUT, COMMUNICATION }
    private final Reason reason;

    public SellerIdentityVerificationException(Reason reason, Integer status, Throwable cause) {
        super(switch (reason) {
            case DISABLED -> Kind.DISABLED;
            case AUTHORIZATION, IDENTITY_MISMATCH -> Kind.AUTHORIZATION;
            case TIMEOUT -> Kind.TIMEOUT;
            case COMMUNICATION -> Kind.COMMUNICATION;
            case ACTIVE_TRANSACTION, INVALID_RESPONSE -> Kind.INVALID_RESPONSE;
        }, status, false, cause);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
    @Override public String getMessage() { return "Vendedor de teste não validado; operação de Orders bloqueada"; }
}
