package br.com.fiap.numberone.billing.payment.domain.exceptions;

public class InvalidPaymentStateException extends RuntimeException {
    public InvalidPaymentStateException(String message) {
        super(message);
    }
}
