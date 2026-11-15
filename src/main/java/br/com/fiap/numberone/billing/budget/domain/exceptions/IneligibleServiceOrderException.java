package br.com.fiap.numberone.billing.budget.domain.exceptions;

public class IneligibleServiceOrderException extends InvalidBudgetDataException {

    public IneligibleServiceOrderException(String message) {
        super(message);
    }
}
