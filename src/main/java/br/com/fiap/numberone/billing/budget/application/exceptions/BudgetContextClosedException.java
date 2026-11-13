package br.com.fiap.numberone.billing.budget.application.exceptions;

public class BudgetContextClosedException extends RuntimeException {
    public BudgetContextClosedException() {
        super("O contexto comercial da ordem de serviço está encerrado");
    }
}
