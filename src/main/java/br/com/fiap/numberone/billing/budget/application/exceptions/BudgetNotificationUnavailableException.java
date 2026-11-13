package br.com.fiap.numberone.billing.budget.application.exceptions;

public class BudgetNotificationUnavailableException extends RuntimeException {
    public BudgetNotificationUnavailableException() {
        this("Não foi possível preparar a notificação de aprovação do orçamento");
    }

    public BudgetNotificationUnavailableException(String message) {
        super(message);
    }

    public BudgetNotificationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
