package br.com.fiap.numberone.billing.payment.application.exceptions;

public class ChargeNotFoundException extends RuntimeException {
    public ChargeNotFoundException() {
        super("Cobrança não encontrada");
    }
}
