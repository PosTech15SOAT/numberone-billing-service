package br.com.fiap.numberone.billing.payment.domain.exceptions;

public class ServiceOrderNotCompletedException extends InvalidPaymentDataException {
    public ServiceOrderNotCompletedException() {
        super("Pagamento exige OS concluída");
    }
}
