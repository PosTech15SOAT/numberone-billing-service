package br.com.fiap.numberone.billing.payment.domain.exceptions;

public class InvalidPaymentDataException extends RuntimeException {
    public InvalidPaymentDataException(String message) {
        super(message);
    }

    public InvalidPaymentDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
