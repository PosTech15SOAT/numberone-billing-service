package br.com.fiap.numberone.billing.budget.domain.exceptions;

public class InvalidBudgetDataException extends RuntimeException {

    public InvalidBudgetDataException(String message) {
        super(message);
    }
}
