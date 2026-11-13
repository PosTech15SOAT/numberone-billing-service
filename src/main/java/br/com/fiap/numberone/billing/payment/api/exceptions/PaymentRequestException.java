package br.com.fiap.numberone.billing.payment.api.exceptions;

/** Impede que o resolver MVC serialize mensagens técnicas externas ao registrar a exceção resolvida. */
public class PaymentRequestException extends RuntimeException {
    public PaymentRequestException(Exception cause) {
        super("Falha controlada na operação de pagamento", cause);
    }
}
