package br.com.fiap.numberone.billing.payment.application.exceptions;

public class PaymentPersistenceConflictException extends RuntimeException {
    public PaymentPersistenceConflictException(Throwable cause) {
        super("Conflito de integridade na persistência do pagamento", cause);
    }
}
