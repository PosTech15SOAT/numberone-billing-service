package br.com.fiap.numberone.billing.budget.domain.exceptions;

public class InvalidBudgetStatusException extends RuntimeException {

    public InvalidBudgetStatusException(String message) {
        super(message);
    }
}
