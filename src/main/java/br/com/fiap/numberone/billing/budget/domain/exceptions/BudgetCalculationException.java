package br.com.fiap.numberone.billing.budget.domain.exceptions;

public class BudgetCalculationException extends InvalidBudgetDataException {

    public BudgetCalculationException(String message) {
        super(message);
    }
}
