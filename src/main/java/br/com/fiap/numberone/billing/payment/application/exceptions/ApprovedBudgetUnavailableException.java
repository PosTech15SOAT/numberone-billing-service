package br.com.fiap.numberone.billing.payment.application.exceptions;

import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentDataException;

public class ApprovedBudgetUnavailableException extends InvalidPaymentDataException {
    public ApprovedBudgetUnavailableException() {
        super("Checkout exige um único orçamento aprovado da mesma OS");
    }
}
