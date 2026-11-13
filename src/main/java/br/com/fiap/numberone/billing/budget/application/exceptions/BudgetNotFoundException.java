package br.com.fiap.numberone.billing.budget.application.exceptions;

public class BudgetNotFoundException extends RuntimeException {

    public BudgetNotFoundException() {
        super("Orçamento não encontrado");
    }
}
