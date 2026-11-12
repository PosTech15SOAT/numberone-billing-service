package br.com.fiap.numberone.billing.budget.application.exceptions;

public class BudgetResendTooSoonException extends RuntimeException {
    public BudgetResendTooSoonException() {
        super("Aguarde o intervalo mínimo antes de solicitar outro envio de aprovação");
    }
}
