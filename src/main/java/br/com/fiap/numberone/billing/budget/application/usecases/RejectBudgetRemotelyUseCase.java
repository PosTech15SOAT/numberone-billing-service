package br.com.fiap.numberone.billing.budget.application.usecases;

import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetAuthorizationException;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;

public class RejectBudgetRemotelyUseCase {
    private final BudgetGateway gateway;
    public RejectBudgetRemotelyUseCase(BudgetGateway gateway) { this.gateway = gateway; }
    public Budget execute(String token, boolean confirmed) {
        if (!confirmed) throw new BudgetAuthorizationException(BudgetAuthorizationException.Reason.CONFIRMATION_REQUIRED);
        return gateway.rejectRemotely(token);
    }
}
